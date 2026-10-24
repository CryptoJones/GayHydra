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
package ghidra.program.model.pcode;

import static ghidra.program.model.pcode.AttributeId.*;
import static ghidra.program.model.pcode.ElementId.*;
import static org.junit.Assert.*;

import java.io.*;
import java.util.ArrayList;

import org.junit.Before;
import org.junit.Test;

import generic.test.AbstractGenericTest;
import ghidra.program.model.address.*;

/**
 * {@link PcodeBlock.BlockEdge} decoding with and without the fork's {@code edgelabel}
 * attribute (#497). The fork's native decompiler always writes it; stock Ghidra's
 * protocol, and cores that follow it such as kuna's, never do.
 */
public class BlockEdgeDecodeTest extends AbstractGenericTest {

	private AddressFactory addrFactory;

	@Before
	public void setUp() {
		AddressSpace spaces[] = new AddressSpace[2];
		spaces[0] = new GenericAddressSpace("ram", 32, AddressSpace.TYPE_RAM, 2);
		spaces[1] = new GenericAddressSpace("const", 32, AddressSpace.TYPE_CONSTANT, 0);
		addrFactory = new DefaultAddressFactory(spaces);
	}

	/** Encode one edge element as the stock protocol does: end and rev only. */
	private static void writeStockEdge(Encoder encoder, int end, int rev) throws IOException {
		encoder.openElement(ELEM_EDGE);
		encoder.writeSignedInteger(ATTRIB_END, end);
		encoder.writeSignedInteger(ATTRIB_REV, rev);
		encoder.closeElement(ELEM_EDGE);
	}

	private PackedDecode decoderFor(PatchPackedEncode encoder)
			throws IOException, DecoderException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		encoder.writeTo(out);
		PackedDecode decoder = new PackedDecode(addrFactory);
		decoder.open(1 << 20, "BlockEdgeDecodeTest");
		decoder.ingestStreamToNextTerminator(new ByteArrayInputStream(out.toByteArray()));
		decoder.endIngest();
		return decoder;
	}

	private static ArrayList<PcodeBlock> blockList(int count) {
		ArrayList<PcodeBlock> list = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			PcodeBlock block = new PcodeBlock();
			block.setIndex(i);
			list.add(block);
		}
		return list;
	}

	@Test
	public void testEdgeWithoutLabelDecodesFromList() throws Exception {
		PatchPackedEncode encoder = new PatchPackedEncode();
		encoder.clear();
		writeStockEdge(encoder, 1, 2);
		ArrayList<PcodeBlock> blocks = blockList(3);

		PcodeBlock.BlockEdge edge = new PcodeBlock.BlockEdge();
		edge.decode(decoderFor(encoder), blocks);

		assertSame(blocks.get(1), edge.point);
		assertEquals(2, edge.reverse_index);
		assertEquals(0, edge.label);
	}

	@Test
	public void testEdgeWithoutLabelDecodesFromBlockMap() throws Exception {
		PatchPackedEncode encoder = new PatchPackedEncode();
		encoder.clear();
		writeStockEdge(encoder, 1, 0);
		BlockMap resolver = new BlockMap(addrFactory);
		resolver.createBlock("plain", 0);
		PcodeBlock target = resolver.createBlock("plain", 1);
		resolver.sortLevelList();

		PcodeBlock.BlockEdge edge = new PcodeBlock.BlockEdge();
		edge.decode(decoderFor(encoder), resolver);

		assertSame(target, edge.point);
		assertEquals(0, edge.reverse_index);
		assertEquals(0, edge.label);
	}

	@Test
	public void testEdgeWithLabelRoundTripsThroughForkEncoder() throws Exception {
		ArrayList<PcodeBlock> blocks = blockList(3);
		PatchPackedEncode encoder = new PatchPackedEncode();
		encoder.clear();
		new PcodeBlock.BlockEdge(blocks.get(2), 5, 1).encode(encoder);

		PcodeBlock.BlockEdge edge = new PcodeBlock.BlockEdge();
		edge.decode(decoderFor(encoder), blocks);

		assertSame(blocks.get(2), edge.point);
		assertEquals(1, edge.reverse_index);
		assertEquals(5, edge.label);
	}

	@Test
	public void testLabelBeforeOtherAttributesIsStillRead() throws Exception {
		// Attribute order is not part of the contract; the label may come first.
		PatchPackedEncode encoder = new PatchPackedEncode();
		encoder.clear();
		encoder.openElement(ELEM_EDGE);
		encoder.writeUnsignedInteger(ATTRIB_EDGE_LABEL, 3);
		encoder.writeSignedInteger(ATTRIB_END, 0);
		encoder.writeSignedInteger(ATTRIB_REV, 4);
		encoder.closeElement(ELEM_EDGE);
		ArrayList<PcodeBlock> blocks = blockList(1);

		PcodeBlock.BlockEdge edge = new PcodeBlock.BlockEdge();
		edge.decode(decoderFor(encoder), blocks);

		assertSame(blocks.get(0), edge.point);
		assertEquals(4, edge.reverse_index);
		assertEquals(3, edge.label);
	}
}
