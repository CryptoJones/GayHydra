/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include "ghidra_process.hh"
#include "flow.hh"
#include "blockaction.hh"
#include "frame_v1.hh"
#include "schema/ipc_command_ids.h"
#include "schema/ipc_lifecycle_codec.h"

#ifdef _WINDOWS
#include <fcntl.h>
#include <io.h>
#endif

#ifdef __REMOTE_SOCKET__
#include "ifacedecomp.hh"
#endif

namespace ghidra {

#ifdef __REMOTE_SOCKET__

static IfaceStatus *ghidra_dcp = (IfaceStatus *)0;
static RemoteSocket *remote = (RemoteSocket *)0;

/// \brief Establish a debug console for decompilation of the given function
///
/// Attempt to connect to a UNIX domain socket and direct the i/o streams to
/// the decompiler console interface.  The socket must have been previously established
/// by another process.
/// From the command-line,  `nc -l -U /tmp/ghidrasocket` for example.
void connect_to_console(Funcdata *fd)

{
  if (remote == (RemoteSocket *)0) {
    remote = make_unique<RemoteSocket>().release();
    if (remote->open("/tmp/ghidrasocket")) {
      ghidra_dcp = make_unique<IfaceTerm>("[ghidradbg]> ",*remote->getInputStream(),*remote->getOutputStream()).release();
      IfaceCapability::registerAllCommands(ghidra_dcp);
    }
  }
  if (!remote->isSocketOpen())
    return;

  IfaceDecompData *decomp_data = (IfaceDecompData *)ghidra_dcp->getData("decompile");
  decomp_data->fd = fd;
  decomp_data->conf = fd->getArch();
  ostream *oldPrintStream = decomp_data->conf->print->getOutputStream();
  bool emitMarkup = decomp_data->conf->print->emitsMarkup();
  decomp_data->conf->setDebugStream(remote->getOutputStream());
  decomp_data->conf->print->setOutputStream(remote->getOutputStream());
  decomp_data->conf->print->setMarkup(false);
  ghidra_dcp->reset();
  mainloop(ghidra_dcp);
  decomp_data->conf->clearAnalysis(fd);
  decomp_data->conf->print->setOutputStream(oldPrintStream);
  decomp_data->conf->print->setMarkup(emitMarkup);
  fd->debugDisable();
  decomp_data->conf->allacts.getCurrent()->clearBreakPoints();
}

#endif

ElementId ELEM_DOC = ElementId("doc",229);
ElementId ELEM_BUDGETEXHAUSTED = ElementId("budgetexhausted",291);	// Rec 35 #35-5a-2

vector<ArchitectureGhidra *> archlist; // List of architectures currently running

map<string,GhidraCommand *> GhidraCapability::commandmap; // List of commands we can receive from Ghidra proper
FrameInStreambuf *GhidraCapability::schemaFrameIn = (FrameInStreambuf *)0; // Schema-payload dispatch (#34-10b); null = pure v0

// Constructing the singleton registers the capability
GhidraDecompCapability GhidraDecompCapability::ghidraDecompCapability;

/// Select the Architecture the command acts on by its registration id, throwing
/// the same JavaError both parameter paths historically threw when the id names
/// no registered architecture. Shared by loadParameters() (v0 burst) and the
/// schema-v1 overrides (#34-10b), which recover the id from their own encodings.
/// \param id is the architecture registration id
void GhidraCommand::resolveArchitecture(int4 id)

{
  if ((id>=0)&&(id<archlist.size()))
    ghidra = archlist[id];

  if (ghidra == (ArchitectureGhidra *)0)
    throw JavaError("decompiler","No architecture registered with decompiler");
  ghidra->clearWarnings();
}

/// This method reads an id selecting the Architecture to act on, but it can be overloaded
/// to read any set of data from the Ghidra client to configure how the command is executed.
/// Individual parameters are read using the method protocol.
void GhidraCommand::loadParameters(void)

{
  int4 id = -1;
  int4 type = ArchitectureGhidra::readToAnyBurst(sin);
  if (type != 14)
    throw JavaError("alignment","Expecting arch id start");
  sin >> dec >> id;
  type = ArchitectureGhidra::readToAnyBurst(sin);
  if (type != 15)
    throw JavaError("alignment","Expecting arch id end");
  resolveArchitecture(id);
}

/// Decode this command's parameters out of a schema-v1 request payload — the
/// FlatBuffers bytes following the command-id byte of a SCHEMA_PAYLOAD frame
/// (#34-10b, DD-0080). Commands not yet migrated keep this default, which
/// surfaces as a Java-side exception through the doitSchema() error path; a
/// well-behaved host never sends a schema payload for such a command (its
/// sender adopts them one command at a time across #34-10b..e).
/// \param buf is the FlatBuffers payload bytes
/// \param len is the payload length in bytes
void GhidraCommand::loadParametersV1(const uint1 *buf,int4 len)

{
  throw JavaError("decompiler","Command does not accept schema-v1 payloads");
}

/// This method sends any warnings accumulated during execution back, but it can be overloaded
/// to send back any kind of information. Individual records are sent using
/// the message protocol.
void GhidraCommand::sendResult(void)

{
  if (ghidra != (ArchitectureGhidra *)0) {
    sout.write("\000\000\001\020",4);
    sout << ghidra->getWarnings();
    sout.write("\000\000\001\021",4);
  }
}

/// This method calls the main overloaded methods:
///   - loadParameters()
///   - rawAction()
///   - sendResult()
///
/// It wraps the sequence with appropriate error handling and message protocol.
/// \return the meta-command (0=continue, 1=terminate) as issued by the command.
int4 GhidraCommand::doit(void)

{
  status = 0;
  sout.write("\000\000\001\006",4); // Command response header
  try {
    loadParameters();
    int4 type = ArchitectureGhidra::readToAnyBurst(sin);
    if (type != 3)
      throw JavaError("alignment","Missing end of command");
    rawAction();
  }
  catch(DecoderError &err) {
    string errmsg;
    errmsg = "Marshaling error: " + err.explain;
    ghidra->printMessage( errmsg );
  }
  catch(JavaError &err) {
    ArchitectureGhidra::passJavaException(sout,err.type,err.explain);
    return status;			// Abort sending any results
  }
  catch(RecovError &err) {
    string errmsg;
    errmsg = "Recoverable Error: " + err.explain;
    ghidra->printMessage( errmsg );
  }
  catch(LowlevelError &err) {
    string errmsg;
    errmsg = "Low-level Error: " + err.explain;
    ghidra->printMessage( errmsg );
  }
  sendResult();
  sout.write("\000\000\001\007",4); // Command response closer
  sout.flush();
  return status;
}

/// The schema-v1 twin of doit() (#34-10b, DD-0080): same response header,
/// error handling, and result protocol, but the parameters arrive decoded
/// from the SCHEMA_PAYLOAD frame body instead of v0 bursts — so there is no
/// end-of-command burst to consume (the frame envelope replaced it). The
/// response direction is deliberately unchanged v0 marshaling until the
/// host grows a v1 response decoder (#34-10f+).
/// \param buf is the FlatBuffers payload (the frame body after the command-id byte)
/// \param len is the payload length in bytes
/// \return the meta-command (0=continue, 1=terminate) as issued by the command.
int4 GhidraCommand::doitSchema(const uint1 *buf,int4 len)

{
  status = 0;
  sout.write("\000\000\001\006",4); // Command response header
  try {
    loadParametersV1(buf,len);
    rawAction();
  }
  catch(DecoderError &err) {
    string errmsg;
    errmsg = "Marshaling error: " + err.explain;
    ghidra->printMessage( errmsg );
  }
  catch(JavaError &err) {
    ArchitectureGhidra::passJavaException(sout,err.type,err.explain);
    return status;			// Abort sending any results
  }
  catch(RecovError &err) {
    string errmsg;
    errmsg = "Recoverable Error: " + err.explain;
    ghidra->printMessage( errmsg );
  }
  catch(LowlevelError &err) {
    string errmsg;
    errmsg = "Low-level Error: " + err.explain;
    ghidra->printMessage( errmsg );
  }
  sendResult();
  sout.write("\000\000\001\007",4); // Command response closer
  sout.flush();
  return status;
}

void RegisterProgram::loadParameters(void)

{
  pspec.clear();
  cspec.clear();
  tspec.clear();
  corespec.clear();
  ArchitectureGhidra::readStringStream(sin,pspec);
  ArchitectureGhidra::readStringStream(sin,cspec);
  ArchitectureGhidra::readStringStream(sin,tspec);
  ArchitectureGhidra::readStringStream(sin,corespec);
}

/// Decode the schema-v1 registerProgram request (#34-10c): the four spec
/// documents arrive as fields of a FlatBuffers RegisterProgramRequest
/// instead of four v0 string bursts. An unset field reads back empty —
/// the same value an empty v0 burst would produce.
void RegisterProgram::loadParametersV1(const uint1 *buf,int4 len)

{
  ipc::RegisterProgramRequestV1 req;
  if (!ipc::decode_register_program_request(buf,(size_t)len,req))
    throw JavaError("alignment","Malformed schema-v1 registerProgram request");
  pspec = req.processor_spec;
  cspec = req.compiler_spec;
  tspec = req.translate_spec;
  corespec = req.core_types_spec;
}


void RegisterProgram::rawAction(void)

{
  int4 i;
  int4 open = -1;
  for(i=0;i<archlist.size();++i) {
    ghidra = archlist[i];
    if (ghidra == (ArchitectureGhidra *)0) {
      open = i;			// Found open slot
    }
  }
  ghidra = make_unique<ArchitectureGhidra>(pspec,cspec,tspec,corespec,sin,sout).release();
  pspec.clear();
  cspec.clear();
  tspec.clear();
  corespec.clear();

  DocumentStorage store;	// temp storage of initialization xml docs
  ghidra->init(store);
  if (open == -1) {
    open = archlist.size();
    archlist.push_back((ArchitectureGhidra *)0);
  }
  archlist[open] = ghidra;
  archid = open;
}

void RegisterProgram::sendResult(void)

{
  sout.write("\000\000\001\016",4);
  sout << dec << archid;
  sout.write("\000\000\001\017",4);
  GhidraCommand::sendResult();
}

void DeregisterProgram::loadParameters(void)

{
  inid = -1;
  int4 type = ArchitectureGhidra::readToAnyBurst(sin);
  if (type!=14)
    throw JavaError("alignment","Expecting deregister id start");
  sin >> dec >> inid;
  type = ArchitectureGhidra::readToAnyBurst(sin);
  if (type!=15)
    throw JavaError("alignment","Expecting deregister id end");
  if ((inid>=0)&&(inid<archlist.size()))
    ghidra = archlist[inid];

  if (ghidra == (ArchitectureGhidra *)0)
    throw JavaError("decompiler","No architecture registered with decompiler");
  ghidra->clearWarnings();
}

/// Decode the schema-v1 deregisterProgram request (#34-10c): program_id
/// carries the same decimal string the v0 burst carried. The id parse and
/// bad-id behaviour mirror FlushNative::loadParametersV1 (kept per-form
/// twins until a third schema id-parser earns extraction).
void DeregisterProgram::loadParametersV1(const uint1 *buf,int4 len)

{
  ipc::DeregisterProgramRequestV1 req;
  if (!ipc::decode_deregister_program_request(buf,(size_t)len,req))
    throw JavaError("alignment","Malformed schema-v1 deregisterProgram request");
  inid = -1;
  try {
    size_t pos = 0;
    inid = std::stoi(req.program_id,&pos);
    if (pos != req.program_id.size())
      inid = -1;
  } catch(...) {
    inid = -1;
  }
  resolveArchitecture(inid);
}

void DeregisterProgram::rawAction(void)

{
#ifdef __REMOTE_SOCKET__
    if (ghidra_dcp != (IfaceStatus *)0)
      delete ghidra_dcp;
    if (remote != (RemoteSocket *)0)
      delete remote;
    ghidra_dcp = (IfaceStatus *)0;
    remote = (RemoteSocket *)0;
#endif
  if (ghidra != (ArchitectureGhidra *)0) {
    res = 1;
    archlist[inid] = (ArchitectureGhidra *)0;
    delete ghidra;
    ghidra = (ArchitectureGhidra *)0;
    status = 1;
  }
  else
    res = 0;
}

void DeregisterProgram::sendResult(void)

{
  sout.write("\000\000\001\016",4);
  sout << dec << res;
  sout.write("\000\000\001\017",4);
  GhidraCommand::sendResult();
}

/// Decode the schema-v1 flushNative request (#34-10b): a FlatBuffers
/// FlushNativeRequest whose program_id carries the same decimal string the
/// v0 burst carried. A malformed buffer is an alignment error; a
/// non-numeric or out-of-range id resolves no architecture and throws the
/// identical JavaError the v0 path throws for a bad id (behaviour parity).
void FlushNative::loadParametersV1(const uint1 *buf,int4 len)

{
  ipc::FlushNativeRequestV1 req;
  if (!ipc::decode_flush_native_request(buf,(size_t)len,req))
    throw JavaError("alignment","Malformed schema-v1 flushNative request");
  int4 id = -1;
  try {
    size_t pos = 0;
    id = std::stoi(req.program_id,&pos);
    if (pos != req.program_id.size())
      id = -1;
  } catch(...) {
    id = -1;
  }
  resolveArchitecture(id);
}

void FlushNative::rawAction(void)

{
  Scope *globscope = ghidra->symboltab->getGlobalScope();
  globscope->clear();		// Clear symbols first as this may delete scopes
  ghidra->symboltab->deleteSubScopes(globscope); // Flush cached function and globals database
  ghidra->types->clearNoncore(); // Reset type information
  ghidra->commentdb->clear();	// Clear any comments
  ghidra->stringManager->clear();	// Clear string decodings
  ghidra->cpool->clear();
  res = 0;
}

void FlushNative::sendResult(void)

{
  sout.write("\000\000\001\016",4);
  sout << dec << res;
  sout.write("\000\000\001\017",4);
  GhidraCommand::sendResult();
}

void DecompileAt::loadParameters(void)

{
  GhidraCommand::loadParameters();
  PackedDecode decoder(ghidra);
  ArchitectureGhidra::readStringStream(sin,decoder);	// Read encoded address directly from in stream
  addr = Address::decode(decoder); 		// Decode for functions address
}

void DecompileAt::rawAction(void) 

{
  Funcdata *fd = ghidra->symboltab->getGlobalScope()->queryFunction(addr);
  if (fd == (Funcdata *)0) {
    ostringstream s;
    s << "Bad decompile address: " << addr.getShortcut();
    addr.printRaw(s);
    s << "\n";
    s << addr.getSpace()->getName() << " may not be a global space in the spec file.";
    throw LowlevelError(s.str());
  }
  if (!fd->isProcStarted()) {
#ifdef __REMOTE_SOCKET__
    connect_to_console(fd);
#endif
    ghidra->allacts.getCurrent()->reset( *fd );
    ghidra->allacts.getCurrent()->perform( *fd );
  }

  sout.write("\000\000\001\016",4);

  if (fd->isProcComplete()) {
    PackedEncode encoder(sout);
    encoder.openElement(ELEM_DOC);
    if (ghidra->budget.engaged() && ghidra->budget.exhausted()) {
      // Rec 35 #35-5a-2: surface a budget-truncated (partial) result as a
      // structured child element naming the exhausted pass, so the Java client
      // can flag DecompileResults.isPartial() without scraping the
      // warning-header text (DD-0010).
      encoder.openElement(ELEM_BUDGETEXHAUSTED);
      encoder.writeString(ATTRIB_NAME, ghidra->budget.exhaustedPass());
      encoder.closeElement(ELEM_BUDGETEXHAUSTED);
    }
    if (ghidra->getSendParamMeasures() && (ghidra->allacts.getCurrentName() == "paramid")) {
      ParamIDAnalysis pidanalysis( fd, true ); // Only send back final prototype
      pidanalysis.encode( encoder, true );
    }
    else {
      if (ghidra->getSendParamMeasures()) {
	ParamIDAnalysis pidanalysis( fd, false );
	pidanalysis.encode( encoder, true );
      }
      fd->encode(encoder,0,ghidra->getSendSyntaxTree());
      if (ghidra->getSendCCode()&&
	  (ghidra->allacts.getCurrentName() == "decompile"))
        ghidra->print->docFunction(fd);
    }
    encoder.closeElement(ELEM_DOC);
  }
  sout.write("\000\000\001\017",4);
}

void StructureGraph::loadParameters(void)

{
  GhidraCommand::loadParameters();

  PackedDecode decoder(ghidra);
  ArchitectureGhidra::readStringStream(sin,decoder);
  ingraph.decode(decoder);
}

void StructureGraph::rawAction(void)

{
  BlockGraph resultgraph;
  vector<FlowBlock *> rootlist;

  resultgraph.buildCopy(ingraph);
  resultgraph.structureLoops(rootlist);
  resultgraph.calcForwardDominator(rootlist);

  CollapseStructure collapse(resultgraph);
  collapse.collapseAll();
  resultgraph.orderBlocks();

  sout.write("\000\000\001\016",4);
  PackedEncode encoder(sout);
  resultgraph.encode(encoder);
  sout.write("\000\000\001\017",4);
  ingraph.clear();
}

void SetAction::loadParameters(void)

{
  GhidraCommand::loadParameters();
  actionstring.clear();
  printstring.clear();
  ArchitectureGhidra::readStringStream(sin,actionstring);
  ArchitectureGhidra::readStringStream(sin,printstring);
}

void SetAction::rawAction(void)

{
  res = false;
  
  if (actionstring.size() != 0)
    ghidra->allacts.setCurrent(actionstring);
  if (printstring.size() != 0) {
    if (printstring == "tree")
      ghidra->setSendSyntaxTree(true);
    else if (printstring == "notree")
      ghidra->setSendSyntaxTree(false);
    else if (printstring == "c")
      ghidra->setSendCCode(true);
    else if (printstring == "noc")
      ghidra->setSendCCode(false);
    else if (printstring == "parammeasures")
      ghidra->setSendParamMeasures(true);
    else if (printstring == "noparammeasures")
      ghidra->setSendParamMeasures(false);
    else if (printstring == "jumpload")
      ghidra->flowoptions |= FlowInfo::record_jumploads;
    else if (printstring == "nojumpload")
      ghidra->flowoptions &= ~((uint4)FlowInfo::record_jumploads);
    else
      throw LowlevelError("Unknown print action: "+printstring);
  }
  res = true;
}

void SetAction::sendResult(void)

{
  if (res)
    ArchitectureGhidra::writeStringStream(sout,"t");
  else
    ArchitectureGhidra::writeStringStream(sout,"f");
  GhidraCommand::sendResult();
}

void SetOptions::loadParameters(void)

{
  GhidraCommand::loadParameters();
  if (decoder != (Decoder *)0)
    delete decoder;
  decoder = make_unique<PackedDecode>(ghidra).release();
  ArchitectureGhidra::readStringStream(sin, *decoder);
}

SetOptions::~SetOptions(void)

{
  if (decoder != (Decoder *)0)
    delete decoder;
}

void SetOptions::rawAction(void)

{
  res = false;

  ghidra->resetDefaults();
  ghidra->options->decode(*decoder);
  delete decoder;
  decoder = (Decoder *)0;
  res = true;
}

void SetOptions::sendResult(void)

{
  if (res)
    ArchitectureGhidra::writeStringStream(sout,"t");
  else
    ArchitectureGhidra::writeStringStream(sout,"f");
  GhidraCommand::sendResult();
}

/// A command is read from the Ghidra client.  The matching GhidraCommand object is
/// looked up in the \b commandmap, and control is handed over to the command,
/// with the i/o streams.  The command must be issued following the proper
/// message protocol (see ArchitectureGhidra::readToAnyBurst) or an exception is thrown.
/// \param sin is the input stream from the client
/// \param out is the output stream to the client
/// \return the result code of the command
int4 GhidraCapability::readCommand(istream &sin,ostream &out)

{
  string function;
  int4 type;

  // Rec 34 #34-10b (DD-0080): schema-payload dispatch. Only at the top of
  // the command loop is the previous frame provably drained, so this is
  // the one point where the inbound frame buf's FLAGS describe the frame
  // about to be consumed. peek() forces the next frame to load (without
  // consuming payload bytes); EOF falls through to the v0 loop so
  // readToAnyBurst stays the sole closed-pipe authority. A flagged frame's
  // body is [u8 command-id][FlatBuffers bytes] — taken whole, so the v0
  // burst scanner never sees schema bytes. An unknown or missing id drains
  // the frame and answers with the existing bad-command response (the
  // DD-0080 protocol error for an out-of-registry id).
  if (schemaFrameIn != (FrameInStreambuf *)0 && sin.peek() != EOF &&
      (schemaFrameIn->lastFlags() & frame_v1::flags::SCHEMA_PAYLOAD) != 0) {
    vector<uint1> body;
    if (schemaFrameIn->takeSchemaPayload(body) && !body.empty())
      function = ipc_v1::name_for_command_id((ipc_v1::CommandId)body[0]);
    map<string,GhidraCommand *>::const_iterator siter = commandmap.find(function);
    if (siter == commandmap.end()) {
      out.write("\000\000\001\006",4); // Command response header
      out.write("\000\000\001\020",4);
      out << "Bad command: " << function;
      out.write("\000\000\001\021",4);
      out.write("\000\000\001\007",4); // Command response closer
      out.flush();
      return 0;
    }
    return (*siter).second->doitSchema(body.data()+1,(int4)(body.size()-1));
  }

  do {
    type = ArchitectureGhidra::readToAnyBurst(sin); // Align ourselves
  } while(type != 2);
  ArchitectureGhidra::readStringStream(sin,function);
  map<string,GhidraCommand *>::const_iterator iter;
  iter = commandmap.find(function);
  if (iter == commandmap.end()) {
    out.write("\000\000\001\006",4); // Command response header
    out.write("\000\000\001\020",4);
    out << "Bad command: " << function;
    out.write("\000\000\001\021",4);
    out.write("\000\000\001\007",4); // Command response closer
    out.flush();
    return 0;
  }
  return (*iter).second->doit();
}

void GhidraCapability::shutDown(void)

{
  map<string,GhidraCommand *>::iterator iter;
  for(iter=commandmap.begin();iter!=commandmap.end();++iter)
    delete (*iter).second;
}

void GhidraDecompCapability::initialize(void)

{
  commandmap["registerProgram"] = make_unique<RegisterProgram>().release();
  commandmap["deregisterProgram"] = make_unique<DeregisterProgram>().release();
  commandmap["flushNative"] = make_unique<FlushNative>().release();
  commandmap["decompileAt"] = make_unique<DecompileAt>().release();
  commandmap["structureGraph"] = make_unique<StructureGraph>().release();
  commandmap["setAction"] = make_unique<SetAction>().release();
  commandmap["setOptions"] = make_unique<SetOptions>().release();
}

/// Catch the signal so the OS doesn't pop up a dialog
/// \param sig is the OS signal (should always be SIGSEGV)
static void segvHandler(int4 sig)

{
  _Exit(1);	// Don't do any cleanup, just die - prevents OS from popping-up a dialog
}

} // End namespace ghidra

int main(int argc,char **argv)

{
  using namespace ghidra;

  signal(SIGSEGV, &segvHandler);  // Exit on SEGV errors
#ifdef _WINDOWS
  // Force i/o streams to be in binary mode
  _setmode(_fileno(stdin), _O_BINARY);
  _setmode(_fileno(stdout), _O_BINARY);
#endif
  AttributeId::initialize();
  ElementId::initialize();
  CapabilityPoint::initializeAll();

  // Rec 33 #33-2.6: connection-start greeting handshake decides framing.
  // A v0 client (every stock Ghidra build) opens with 0x00 and is left
  // byte-identical for the legacy readCommand loop below. A v1 client
  // opens with the greeting MAGIC; on a successful exchange we tunnel the
  // legacy command-loop bytes through v1 frames by swapping cin/cout's
  // streambufs: each underflow yields one inbound frame's payload and each
  // flush emits one RESPONSE frame. The v0 marshaling inside the frames is
  // untouched, so readCommand and every ArchitectureGhidra callback query
  // ride transparently (each flush already aligns with one logical burst).
  // ArchitectureGhidra, built later holding cin/cout by reference, inherits
  // the swapped bufs automatically. The bufs live for the process lifetime
  // (make_unique(...).release() — the intentional process-lifetime leak this
  // file already uses for every long-lived singleton, and the form the RAII
  // audit accepts; rdbuf() does not take ownership, so the buf must outlive
  // the stream and is reclaimed only at process exit).
  // #34-10b (DD-0080): the worker's dispatch now decodes schema-v1 request
  // payloads, so the greeting advertises SCHEMA_V1_REQUESTS — the capability
  // bit a host checks before sending a SCHEMA_PAYLOAD frame. The host's
  // capabilities come back through peer_capabs (unused until the worker
  // emits v1 responses, #34-10f+). The inbound frame buf is handed to the
  // dispatch so readCommand can observe per-frame FLAGS at command
  // boundaries; a v0 channel installs nothing and stays byte-identical.
  static const string ghidraDecompIdent = "GayHydra-decompiler (v1 framing)";
  uint4 peer_capabs = 0;
  if (negotiate_greeting_v1(cin, cout, ghidraDecompIdent,
                            frame_v1::capab::SCHEMA_V1_REQUESTS,
                            peer_capabs) == frame_v1::ChannelMode::V1) {
    FrameInStreambuf *finbuf = make_unique<FrameInStreambuf>(cin.rdbuf()).release();
    cin.rdbuf(finbuf);
    cout.rdbuf(make_unique<FrameOutStreambuf>(cout.rdbuf(), frame_v1::Type::RESPONSE).release());
    GhidraCapability::enableSchemaDispatch(finbuf);
  }

  int4 status = 0;
  while(status == 0) {
    status = GhidraCapability::readCommand(cin,cout);
  }
  GhidraCapability::shutDown();
}
