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
#include "opbehavior.hh"
#include "translate.hh"

namespace ghidra {

/// This routine generates a vector of OpBehavior objects indexed by opcode
/// \param inst is the vector of behaviors to be filled
/// \param trans is the translator object needed by the floating point behaviors
void OpBehavior::registerInstructions(vector<OpBehavior *> &inst,const Translate *trans)

{
  inst.insert(inst.end(),CPUI_MAX,(OpBehavior *)0);

  inst[CPUI_COPY] = make_unique<OpBehaviorCopy>().release();
  inst[CPUI_LOAD] = make_unique<OpBehavior>(CPUI_LOAD,false,true).release();
  inst[CPUI_STORE] = make_unique<OpBehavior>(CPUI_STORE,false,true).release();
  inst[CPUI_BRANCH] = make_unique<OpBehavior>(CPUI_BRANCH,false,true).release();
  inst[CPUI_CBRANCH] = make_unique<OpBehavior>(CPUI_CBRANCH,false,true).release();
  inst[CPUI_BRANCHIND] = make_unique<OpBehavior>(CPUI_BRANCHIND,false,true).release();
  inst[CPUI_CALL] = make_unique<OpBehavior>(CPUI_CALL,false,true).release();
  inst[CPUI_CALLIND] = make_unique<OpBehavior>(CPUI_CALLIND,false,true).release();
  inst[CPUI_CALLOTHER] = make_unique<OpBehavior>(CPUI_CALLOTHER,false,true).release();
  inst[CPUI_RETURN] = make_unique<OpBehavior>(CPUI_RETURN,false,true).release();

  inst[CPUI_MULTIEQUAL] = make_unique<OpBehavior>(CPUI_MULTIEQUAL,false,true).release();
  inst[CPUI_INDIRECT] = make_unique<OpBehavior>(CPUI_INDIRECT,false,true).release();

  inst[CPUI_PIECE] = make_unique<OpBehaviorPiece>().release();
  inst[CPUI_SUBPIECE] = make_unique<OpBehaviorSubpiece>().release();
  inst[CPUI_INT_EQUAL] = make_unique<OpBehaviorEqual>().release();
  inst[CPUI_INT_NOTEQUAL] = make_unique<OpBehaviorNotEqual>().release();
  inst[CPUI_INT_SLESS] = make_unique<OpBehaviorIntSless>().release();
  inst[CPUI_INT_SLESSEQUAL] = make_unique<OpBehaviorIntSlessEqual>().release();
  inst[CPUI_INT_LESS] = make_unique<OpBehaviorIntLess>().release();
  inst[CPUI_INT_LESSEQUAL] = make_unique<OpBehaviorIntLessEqual>().release();
  inst[CPUI_INT_ZEXT] = make_unique<OpBehaviorIntZext>().release();
  inst[CPUI_INT_SEXT] = make_unique<OpBehaviorIntSext>().release();
  inst[CPUI_INT_ADD] = make_unique<OpBehaviorIntAdd>().release();
  inst[CPUI_INT_SUB] = make_unique<OpBehaviorIntSub>().release();
  inst[CPUI_INT_CARRY] = make_unique<OpBehaviorIntCarry>().release();
  inst[CPUI_INT_SCARRY] = make_unique<OpBehaviorIntScarry>().release();
  inst[CPUI_INT_SBORROW] = make_unique<OpBehaviorIntSborrow>().release();
  inst[CPUI_INT_2COMP] = make_unique<OpBehaviorInt2Comp>().release();
  inst[CPUI_INT_NEGATE] = make_unique<OpBehaviorIntNegate>().release();
  inst[CPUI_INT_XOR] = make_unique<OpBehaviorIntXor>().release();
  inst[CPUI_INT_AND] = make_unique<OpBehaviorIntAnd>().release();
  inst[CPUI_INT_OR] = make_unique<OpBehaviorIntOr>().release();
  inst[CPUI_INT_LEFT] = make_unique<OpBehaviorIntLeft>().release();
  inst[CPUI_INT_RIGHT] = make_unique<OpBehaviorIntRight>().release();
  inst[CPUI_INT_SRIGHT] = make_unique<OpBehaviorIntSright>().release();
  inst[CPUI_INT_MULT] = make_unique<OpBehaviorIntMult>().release();
  inst[CPUI_INT_DIV] = make_unique<OpBehaviorIntDiv>().release();
  inst[CPUI_INT_SDIV] = make_unique<OpBehaviorIntSdiv>().release();
  inst[CPUI_INT_REM] = make_unique<OpBehaviorIntRem>().release();
  inst[CPUI_INT_SREM] = make_unique<OpBehaviorIntSrem>().release();

  inst[CPUI_BOOL_NEGATE] = make_unique<OpBehaviorBoolNegate>().release();
  inst[CPUI_BOOL_XOR] = make_unique<OpBehaviorBoolXor>().release();
  inst[CPUI_BOOL_AND] = make_unique<OpBehaviorBoolAnd>().release();
  inst[CPUI_BOOL_OR] = make_unique<OpBehaviorBoolOr>().release();

  inst[CPUI_CAST] = make_unique<OpBehavior>(CPUI_CAST,false,true).release();
  inst[CPUI_PTRADD] = make_unique<OpBehavior>(CPUI_PTRADD,false).release();
  inst[CPUI_PTRSUB] = make_unique<OpBehavior>(CPUI_PTRSUB,false).release();

  inst[CPUI_FLOAT_EQUAL] = make_unique<OpBehaviorFloatEqual>(trans).release();
  inst[CPUI_FLOAT_NOTEQUAL] = make_unique<OpBehaviorFloatNotEqual>(trans).release();
  inst[CPUI_FLOAT_LESS] = make_unique<OpBehaviorFloatLess>(trans).release();
  inst[CPUI_FLOAT_LESSEQUAL] = make_unique<OpBehaviorFloatLessEqual>(trans).release();
  inst[CPUI_FLOAT_NAN] = make_unique<OpBehaviorFloatNan>(trans).release();

  inst[CPUI_FLOAT_ADD] = make_unique<OpBehaviorFloatAdd>(trans).release();
  inst[CPUI_FLOAT_DIV] = make_unique<OpBehaviorFloatDiv>(trans).release();
  inst[CPUI_FLOAT_MULT] = make_unique<OpBehaviorFloatMult>(trans).release();
  inst[CPUI_FLOAT_SUB] = make_unique<OpBehaviorFloatSub>(trans).release();
  inst[CPUI_FLOAT_NEG] = make_unique<OpBehaviorFloatNeg>(trans).release();
  inst[CPUI_FLOAT_ABS] = make_unique<OpBehaviorFloatAbs>(trans).release();
  inst[CPUI_FLOAT_SQRT] = make_unique<OpBehaviorFloatSqrt>(trans).release();

  inst[CPUI_FLOAT_INT2FLOAT] = make_unique<OpBehaviorFloatInt2Float>(trans).release();
  inst[CPUI_FLOAT_FLOAT2FLOAT] = make_unique<OpBehaviorFloatFloat2Float>(trans).release();
  inst[CPUI_FLOAT_TRUNC] = make_unique<OpBehaviorFloatTrunc>(trans).release();
  inst[CPUI_FLOAT_CEIL] = make_unique<OpBehaviorFloatCeil>(trans).release();
  inst[CPUI_FLOAT_FLOOR] = make_unique<OpBehaviorFloatFloor>(trans).release();
  inst[CPUI_FLOAT_ROUND] = make_unique<OpBehaviorFloatRound>(trans).release();
  inst[CPUI_SEGMENTOP] = make_unique<OpBehavior>(CPUI_SEGMENTOP,false,true).release();
  inst[CPUI_CPOOLREF] = make_unique<OpBehavior>(CPUI_CPOOLREF,false,true).release();
  inst[CPUI_NEW] = make_unique<OpBehavior>(CPUI_NEW,false,true).release();
  inst[CPUI_INSERT] = make_unique<OpBehavior>(CPUI_INSERT,false).release();
  inst[CPUI_ZPULL] = make_unique<OpBehavior>(CPUI_ZPULL,false).release();
  inst[CPUI_POPCOUNT] = make_unique<OpBehaviorPopcount>().release();
  inst[CPUI_LZCOUNT] = make_unique<OpBehaviorLzcount>().release();
  inst[CPUI_SPULL] = make_unique<OpBehavior>(CPUI_SPULL,false).release();
}

/// \param sizeout is the size of the output in bytes
/// \param sizein is the size of the input in bytes
/// \param in1 is the input value
/// \return the output value
uintb OpBehavior::evaluateUnary(int4 sizeout,int4 sizein,uintb in1) const

{
  string name(get_opname(opcode));
  throw LowlevelError("Unary emulation unimplemented for "+name);
}

/// \param sizeout is the size of the output in bytes
/// \param sizein is the size of the inputs in bytes
/// \param in1 is the first input value
/// \param in2 is the second input value
/// \return the output value
uintb OpBehavior::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  string name(get_opname(opcode));
  throw LowlevelError("Binary emulation unimplemented for "+name);
}

/// \param sizeout is the size of the output in bytes
/// \param sizein is the size of the inputs in bytes
/// \param in1 is the first input value
/// \param in2 is the second input value
/// \param in3 is the third input value
/// \return the output value
uintb OpBehavior::evaluateTernary(int4 sizeout,int4 sizein,uintb in1,uintb in2,uintb in3) const

{
  string name(get_opname(opcode));
  throw LowlevelError("Ternary emulation unimplemented for "+name);
}

/// If the output value is known, recover the input value.
/// \param sizeout is the size of the output in bytes
/// \param out is the output value
/// \param sizein is the size of the input in bytes
/// \return the input value
uintb OpBehavior::recoverInputUnary(int4 sizeout,uintb out,int4 sizein) const

{
  throw LowlevelError("Cannot recover input parameter without loss of information");
}

/// If the output value and one of the input values is known, recover the value
/// of the other input.
/// \param slot is the input slot to recover
/// \param sizeout is the size of the output in bytes
/// \param out is the output value
/// \param sizein is the size of the inputs in bytes
/// \param in is the known input value
/// \return the input value corresponding to the \b slot
uintb OpBehavior::recoverInputBinary(int4 slot,int4 sizeout,uintb out,int4 sizein,uintb in) const

{
  throw LowlevelError("Cannot recover input parameter without loss of information");
}

uintb OpBehaviorCopy::evaluateUnary(int4 sizeout,int4 sizein,uintb in1) const

{
  return in1;
}

uintb OpBehaviorCopy::recoverInputUnary(int4 sizeout,uintb out,int4 sizein) const

{
  return out;
}

uintb OpBehaviorEqual::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  uintb res = (in1 == in2) ? 1 : 0;
  return res;
}

uintb OpBehaviorNotEqual::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  uintb res = (in1 != in2) ? 1 : 0;
  return res;
}

uintb OpBehaviorIntSless::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  uintb res,mask,bit1,bit2;

  if (sizein<=0)
    res = 0;
  else {
    mask = 0x80;
    mask <<= 8*(sizein-1);
    bit1 = in1 & mask;		// Get the sign bits
    bit2 = in2 & mask;
    if (bit1 != bit2)
      res = (bit1 != 0) ? 1 : 0;
    else
      res = (in1 < in2) ? 1 : 0;
  }
  return res;
}

uintb OpBehaviorIntSlessEqual::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  uintb res,mask,bit1,bit2;

  if (sizein<=0)
    res = 0;
  else {
    mask = 0x80;
    mask <<= 8*(sizein-1);
    bit1 = in1 & mask;		// Get the sign bits
    bit2 = in2 & mask;
    if (bit1 != bit2)
      res = (bit1 != 0) ? 1 : 0;
    else
      res = (in1 <= in2) ? 1 : 0;
  }
  return res;
}

uintb OpBehaviorIntLess::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  uintb res = (in1 < in2) ? 1 : 0;
  return res;
}

uintb OpBehaviorIntLessEqual::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  uintb res = (in1 <= in2) ? 1 : 0;
  return res;
}

uintb OpBehaviorIntZext::evaluateUnary(int4 sizeout,int4 sizein,uintb in1) const

{
  return in1;
}

uintb OpBehaviorIntZext::recoverInputUnary(int4 sizeout,uintb out,int4 sizein) const

{
  uintb mask = calc_mask(sizein);
  if ((mask&out)!=out)
    throw EvaluationError("Output is not in range of zext operation");
  return out;
}

uintb OpBehaviorIntSext::evaluateUnary(int4 sizeout,int4 sizein,uintb in1) const

{
  uintb res = sign_extend(in1,sizein,sizeout);
  return res;
}

uintb OpBehaviorIntSext::recoverInputUnary(int4 sizeout,uintb out,int4 sizein) const

{
  uintb masklong = calc_mask(sizeout);
  uintb maskshort = calc_mask(sizein);

  if ((out & (maskshort ^ (maskshort>>1))) == 0) { // Positive input
    if ((out & maskshort) != out)
      throw EvaluationError("Output is not in range of sext operation");
  }
  else {			// Negative input
    if ((out & (masklong^maskshort)) != (masklong^maskshort))
      throw EvaluationError("Output is not in range of sext operation");
  }
  return (out&maskshort);
}

uintb OpBehaviorIntAdd::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  uintb res = (in1 + in2) & calc_mask(sizeout);
  return res;
}

uintb OpBehaviorIntAdd::recoverInputBinary(int4 slot,int4 sizeout,uintb out,int4 sizein,uintb in) const

{
  uintb res = (out-in) & calc_mask(sizeout);
  return res;
}

uintb OpBehaviorIntSub::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  uintb res = (in1 - in2) & calc_mask(sizeout);
  return res;
}

uintb OpBehaviorIntSub::recoverInputBinary(int4 slot,int4 sizeout,uintb out,int4 sizein,uintb in) const

{
  uintb res;
  if (slot==0)
    res = in + out;
  else
    res = in - out;
  res &= calc_mask(sizeout);
  return res;
}

uintb OpBehaviorIntCarry::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  uintb res = (in1 > ((in1 + in2)&calc_mask(sizein))) ? 1 : 0;
  return res;
}

uintb OpBehaviorIntScarry::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  uintb res = in1 + in2;

  uint4 a = (in1>>(sizein*8-1))&1; // Grab sign bit
  uint4 b = (in2>>(sizein*8-1))&1; // Grab sign bit
  uint4 r = (res>>(sizein*8-1))&1; // Grab sign bit
  
  r ^= a;
  a ^= b;
  a ^= 1;
  r &= a;
  return (uintb)r;
}

uintb OpBehaviorIntSborrow::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  uintb res = in1 - in2;

  uint4 a = (in1>>(sizein*8-1))&1; // Grab sign bit
  uint4 b = (in2>>(sizein*8-1))&1; // Grab sign bit
  uint4 r = (res>>(sizein*8-1))&1; // Grab sign bit

  a ^= r;
  r ^= b;
  r ^= 1;
  a &= r;
  return (uintb)a;
}

uintb OpBehaviorInt2Comp::evaluateUnary(int4 sizeout,int4 sizein,uintb in1) const

{
  uintb res = uintb_negate(in1-1,sizein);
  return res;
}

uintb OpBehaviorInt2Comp::recoverInputUnary(int4 sizeout,uintb out,int4 sizein) const

{
  uintb res = uintb_negate(out-1,sizein);
  return res;
}

uintb OpBehaviorIntNegate::evaluateUnary(int4 sizeout,int4 sizein,uintb in1) const

{
  uintb res = uintb_negate(in1,sizein);
  return res;
}

uintb OpBehaviorIntNegate::recoverInputUnary(int4 sizeout,uintb out,int4 sizein) const

{
  uintb res = uintb_negate(out,sizein);
  return res;
}

uintb OpBehaviorIntXor::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  uintb res = in1 ^ in2;
  return res;
}

uintb OpBehaviorIntAnd::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  uintb res = in1 & in2;
  return res;
}

uintb OpBehaviorIntOr::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  uintb res = in1 | in2;
  return res;
}

uintb OpBehaviorIntLeft::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
    if (in2 >= sizeout*8){
    	return 0;
    }
	uintb res = (in1 << in2) & calc_mask(sizeout);
    return res;
}

uintb OpBehaviorIntLeft::recoverInputBinary(int4 slot,int4 sizeout,uintb out,int4 sizein,uintb in) const

{
  if ((slot!=0) || (in >= sizeout*8))
    return OpBehavior::recoverInputBinary(slot,sizeout,out,sizein,in);
  int4 sa = in;
  if (((out<<(8*sizeout-sa))&calc_mask(sizeout))!=0)
    throw EvaluationError("Output is not in range of left shift operation");
  return out >> sa;
}

uintb OpBehaviorIntRight::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  if (in2 >= sizeout*8){
	 return 0;
  }
  uintb res = (in1&calc_mask(sizeout)) >> in2;
  return res;
}

uintb OpBehaviorIntRight::recoverInputBinary(int4 slot,int4 sizeout,uintb out,int4 sizein,uintb in) const

{
  if ((slot!=0) || (in >= sizeout*8))
    return OpBehavior::recoverInputBinary(slot,sizeout,out,sizein,in);
  
  int4 sa = in;
  if ((out>>(8*sizein-sa))!=0)
    throw EvaluationError("Output is not in range of right shift operation");
  return out << sa;
}

uintb OpBehaviorIntSright::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  if (in2 >= 8*sizeout){
	  return signbit_negative(in1,sizein) ? calc_mask(sizeout) : 0;
  }

  uintb res;
  if (signbit_negative(in1,sizein)) {
    res = in1 >> in2;
    uintb mask = calc_mask(sizein);
    mask = (mask >> in2) ^ mask;
    res |= mask;
  }
  else {
    res = in1 >> in2;
  }
  return res;
}

uintb OpBehaviorIntSright::recoverInputBinary(int4 slot,int4 sizeout,uintb out,int4 sizein,uintb in) const

{
  if ((slot!=0) || (in >= sizeout*8))
    return OpBehavior::recoverInputBinary(slot,sizeout,out,sizein,in);
  
  int4 sa = in;
  uintb testval = out>>(sizein*8-sa-1);
  int4 count=0;
  for(int4 i=0;i<=sa;++i) {
    if ((testval&1)!=0) count += 1;
    testval >>= 1;
  }
  if (count != sa+1)
    throw EvaluationError("Output is not in range of right shift operation");
  return out<<sa;
}

uintb OpBehaviorIntMult::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  uintb res = (in1 * in2) & calc_mask(sizeout);
  return res;
}

uintb OpBehaviorIntDiv::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  if (in2 == 0)
    throw EvaluationError("Divide by 0");
  return in1 / in2;
}

uintb OpBehaviorIntSdiv::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  if (in2 == 0)
    throw EvaluationError("Divide by 0");
  intb num = sign_extend(in1,8*sizein-1);		// Convert to signed
  intb denom = sign_extend(in2,8*sizein-1);
  intb sres = num/denom;	// Do the signed division
  sres = zero_extend(sres,8*sizeout-1); // Cut to appropriate size
  return (uintb)sres;		// Recast as unsigned
}

uintb OpBehaviorIntRem::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  if (in2 == 0)
    throw EvaluationError("Remainder by 0");
  
  uintb res = in1 % in2;
  return res;
}

uintb OpBehaviorIntSrem::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  if (in2 == 0)
    throw EvaluationError("Remainder by 0");
  intb val = sign_extend(in1,8*sizein-1);	// Convert inputs to signed values
  intb mod = sign_extend(in2,8*sizein-1);
  intb sres = val % mod;	// Do the remainder
  sres = zero_extend(sres,8*sizeout-1); // Convert back to unsigned
  return (uintb)sres;
}

uintb OpBehaviorBoolNegate::evaluateUnary(int4 sizeout,int4 sizein,uintb in1) const

{
  uintb res = in1 ^ 1;
  return res;
}

uintb OpBehaviorBoolXor::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  uintb res = in1 ^ in2;
  return res;
}

uintb OpBehaviorBoolAnd::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  uintb res = in1 & in2;
  return res;
}

uintb OpBehaviorBoolOr::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  uintb res = in1 | in2;
  return res;
}

uintb OpBehaviorFloatEqual::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  const FloatFormat *format = translate->getFloatFormat(sizein);
  if (format == (const FloatFormat *)0)
    return OpBehavior::evaluateBinary(sizeout,sizein,in1,in2);

  return format->opEqual(in1,in2);
}

uintb OpBehaviorFloatNotEqual::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  const FloatFormat *format = translate->getFloatFormat(sizein);
  if (format == (const FloatFormat *)0)
    return OpBehavior::evaluateBinary(sizeout,sizein,in1,in2);

  return format->opNotEqual(in1,in2);
}

uintb OpBehaviorFloatLess::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  const FloatFormat *format = translate->getFloatFormat(sizein);
  if (format == (const FloatFormat *)0)
    return OpBehavior::evaluateBinary(sizeout,sizein,in1,in2);

  return format->opLess(in1,in2);
}

uintb OpBehaviorFloatLessEqual::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  const FloatFormat *format = translate->getFloatFormat(sizein);
  if (format == (const FloatFormat *)0)
    return OpBehavior::evaluateBinary(sizeout,sizein,in1,in2);

  return format->opLessEqual(in1,in2);
}

uintb OpBehaviorFloatNan::evaluateUnary(int4 sizeout,int4 sizein,uintb in1) const

{
  const FloatFormat *format = translate->getFloatFormat(sizein);
  if (format == (const FloatFormat *)0)
    return OpBehavior::evaluateUnary(sizeout,sizein,in1);

  return format->opNan(in1);
}

uintb OpBehaviorFloatAdd::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  const FloatFormat *format = translate->getFloatFormat(sizein);
  if (format == (const FloatFormat *)0)
    return OpBehavior::evaluateBinary(sizeout,sizein,in1,in2);

  return format->opAdd(in1,in2);
}

uintb OpBehaviorFloatDiv::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  const FloatFormat *format = translate->getFloatFormat(sizein);
  if (format == (const FloatFormat *)0)
    return OpBehavior::evaluateBinary(sizeout,sizein,in1,in2);

  return format->opDiv(in1,in2);
}

uintb OpBehaviorFloatMult::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  const FloatFormat *format = translate->getFloatFormat(sizein);
  if (format == (const FloatFormat *)0)
    return OpBehavior::evaluateBinary(sizeout,sizein,in1,in2);

  return format->opMult(in1,in2);
}

uintb OpBehaviorFloatSub::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  const FloatFormat *format = translate->getFloatFormat(sizein);
  if (format == (const FloatFormat *)0)
    return OpBehavior::evaluateBinary(sizeout,sizein,in1,in2);

  return format->opSub(in1,in2);
}

uintb OpBehaviorFloatNeg::evaluateUnary(int4 sizeout,int4 sizein,uintb in1) const

{
  const FloatFormat *format = translate->getFloatFormat(sizein);
  if (format == (const FloatFormat *)0)
    return OpBehavior::evaluateUnary(sizeout,sizein,in1);

  return format->opNeg(in1);
}

uintb OpBehaviorFloatAbs::evaluateUnary(int4 sizeout,int4 sizein,uintb in1) const

{
  const FloatFormat *format = translate->getFloatFormat(sizein);
  if (format == (const FloatFormat *)0)
    return OpBehavior::evaluateUnary(sizeout,sizein,in1);

  return format->opAbs(in1);
}

uintb OpBehaviorFloatSqrt::evaluateUnary(int4 sizeout,int4 sizein,uintb in1) const

{
  const FloatFormat *format = translate->getFloatFormat(sizein);
  if (format == (const FloatFormat *)0)
    return OpBehavior::evaluateUnary(sizeout,sizein,in1);

  return format->opSqrt(in1);
}

uintb OpBehaviorFloatInt2Float::evaluateUnary(int4 sizeout,int4 sizein,uintb in1) const

{
  const FloatFormat *format = translate->getFloatFormat(sizeout);
  if (format == (const FloatFormat *)0)
    return OpBehavior::evaluateUnary(sizeout,sizein,in1);

  return format->opInt2Float(in1,sizein);
}

uintb OpBehaviorFloatFloat2Float::evaluateUnary(int4 sizeout,int4 sizein,uintb in1) const

{
  const FloatFormat *formatout = translate->getFloatFormat(sizeout);
  if (formatout == (const FloatFormat *)0)
    return OpBehavior::evaluateUnary(sizeout,sizein,in1);
  const FloatFormat *formatin = translate->getFloatFormat(sizein);
  if (formatin == (const FloatFormat *)0)
    return OpBehavior::evaluateUnary(sizeout,sizein,in1);

  return formatin->opFloat2Float(in1,*formatout);
}

uintb OpBehaviorFloatTrunc::evaluateUnary(int4 sizeout,int4 sizein,uintb in1) const

{
  const FloatFormat *format = translate->getFloatFormat(sizein);
  if (format == (const FloatFormat *)0)
    return OpBehavior::evaluateUnary(sizeout,sizein,in1);

  return format->opTrunc(in1,sizeout);
}

uintb OpBehaviorFloatCeil::evaluateUnary(int4 sizeout,int4 sizein,uintb in1) const

{
  const FloatFormat *format = translate->getFloatFormat(sizein);
  if (format == (const FloatFormat *)0)
    return OpBehavior::evaluateUnary(sizeout,sizein,in1);

  return format->opCeil(in1);
}

uintb OpBehaviorFloatFloor::evaluateUnary(int4 sizeout,int4 sizein,uintb in1) const

{
  const FloatFormat *format = translate->getFloatFormat(sizein);
  if (format == (const FloatFormat *)0)
    return OpBehavior::evaluateUnary(sizeout,sizein,in1);

  return format->opFloor(in1);
}

uintb OpBehaviorFloatRound::evaluateUnary(int4 sizeout,int4 sizein,uintb in1) const

{
  const FloatFormat *format = translate->getFloatFormat(sizein);
  if (format == (const FloatFormat *)0)
    return OpBehavior::evaluateUnary(sizeout,sizein,in1);

  return format->opRound(in1);
}

uintb OpBehaviorPiece::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  uintb res = ( in1<<((sizeout-sizein)*8)) | in2;
  return res;
}

uintb OpBehaviorSubpiece::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  if (in2 >= sizeof(uintb))
    return 0;
  uintb res = (in1>>(in2*8)) & calc_mask(sizeout);
  return res;
}

uintb OpBehaviorPtradd::evaluateTernary(int4 sizeout,int4 sizein,uintb in1,uintb in2,uintb in3) const

{
  uintb res = (in1 + in2 * in3) & calc_mask(sizeout);
  return res;
}

uintb OpBehaviorPtrsub::evaluateBinary(int4 sizeout,int4 sizein,uintb in1,uintb in2) const

{
  uintb res = (in1 + in2) & calc_mask(sizeout);
  return res;
}

uintb OpBehaviorPopcount::evaluateUnary(int4 sizeout,int4 sizein,uintb in1) const

{
  return (uintb)popcount(in1);
}

uintb OpBehaviorLzcount::evaluateUnary(int4 sizeout,int4 sizein,uintb in1) const

{
  return (uintb)(count_leading_zeros(in1) - 8*(sizeof(uintb) - sizein));
}

} // End namespace ghidra
