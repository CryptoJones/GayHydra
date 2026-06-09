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
package ghidra.app.util.cpp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import ghidra.app.util.cpp.CppPlacementConstructionRecognizer.PlacementConstruction;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.AbstractFloatDataType;
import ghidra.program.model.data.AbstractIntegerDataType;
import ghidra.program.model.data.BooleanDataType;
import ghidra.program.model.data.CharDataType;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.Enum;
import ghidra.program.model.data.WideChar16DataType;
import ghidra.program.model.data.WideChar32DataType;
import ghidra.program.model.data.WideCharDataType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Program;
import ghidra.program.model.pcode.HighFunction;
import ghidra.program.model.pcode.HighVariable;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.pcode.PcodeOpAST;
import ghidra.program.model.pcode.Varnode;
import ghidra.program.model.symbol.Namespace;

/**
 * The driver half of Rec 37 {@code #37-9e-b}: walks a decompiled {@link HighFunction}, uses the
 * {@link CppPlacementConstructionRecognizer} matcher ({@code #37-9e-b-1}, DD-0037) to find candidate
 * non-elided placement-{@code new} constructions, resolves the recovered constructor and allocation
 * targets to {@link Function}s, confirms the first is a constructor and the second is
 * {@code operator new}, recovers the placement buffer's expression, resolves the constructed
 * {@link CppClass} in a supplied {@link CppTypeSystem}, and dispatches to the stateless
 * {@link CppDecompilerHints#renderPlacementConstruction} renderer (DD-0016) to produce the C++ hint
 * string. This is the {@code #37-9e-b-2} slice that closes the loop the matcher opened &mdash; the
 * <b>seventh and last</b> Rec 37 recognition form to go end-to-end.
 *
 * <p><b>The same two callee classifications as heap {@code new}, plus the buffer.</b> Like the
 * {@code #37-9b} {@link CppConstructorDriver} (DD-0031), the driver holds the {@link Program} and
 * classifies the two recovered callees from their demangled names: the constructor by the
 * {@code name == class} marker (its local name equals its parent class namespace), and the allocation
 * as {@code operator new} (its {@code .} namespace separator normalised to a space). What it adds over
 * the heap driver is the <em>placement target</em>: it renders the recovered buffer varnode's
 * {@link HighVariable} name as the placement expression &mdash; the same operand-rendering the delete,
 * destructor, and cast drivers use &mdash; and dispatches to {@code renderPlacementConstruction}, which
 * emits {@code new (buf) ClassName(args)}. (The two small name classifiers are duplicated from the heap
 * driver rather than extracted: at this second user they are kept as honest per-form twins, per the
 * DD-0026 rule-of-three convention, until a third user earns the extraction.)
 *
 * <p><b>Explicit constructor arguments are threaded</b> ({@code #37-10a}, {@code #37-10c}&ndash;{@code #37-10e}):
 * the constructor {@code CALL}'s inputs after the call target (index 0) and the {@code this} receiver
 * (index 1) are its explicit arguments. A named argument renders as its {@link HighVariable} name, a
 * {@code bool} constant as {@code true}/{@code false}, and an integer-typed constant as its decimal value
 * &mdash; read at the varnode's byte width, so a negative argument renders {@code -1}, not a large
 * unsigned number &mdash; so {@code new (buf) ClassName(arg)}, {@code new (buf) ClassName(5)}, and
 * {@code new (buf) ClassName(true)} all render; a zero-argument constructor still renders
 * {@code new (buf) ClassName()}. An argument that is neither named nor a boolean/integer constant (an
 * unnamed non-constant temporary, or a non-integer constant such as a pointer-typed global address)
 * declines the whole hint rather than rendering a gap or a misleading bare number. Rendering compound
 * argument expressions and the remaining typed constants (chars, enum names) is later {@code #37-10} work,
 * as is overload
 * resolution against the {@code DataType} signature.
 *
 * <p><b>Advisory, never wrong.</b> Like the matcher and renderer it sits between, the driver is
 * additive and total-failure-safe: a construction whose constructor target resolves to no function or
 * is not a {@code name == class} constructor, whose allocation target is not {@code operator new},
 * whose placement buffer has no printable name, or whose class is not modelled in the type system is
 * silently skipped (it contributes no hint), never mis-rendered or raised as an error. A function with
 * no recognised placement construction yields an empty list.
 */
public final class CppPlacementConstructionDriver {

	/**
	 * A rendered placement-construction hint: the {@code site} address of the constructor {@code CALL}
	 * it was recovered from, and the {@code rendering} string the {@link CppDecompilerHints} renderer
	 * produced.
	 *
	 * @param site the address of the dispatching constructor call op
	 * @param rendering the rendered C++ placement-{@code new} expression
	 */
	public record RenderedPlacement(Address site, String rendering) {}

	/** A constructor {@code CALL}'s input 0 is the call target; input 1 is the {@code this} receiver. */
	private static final int THIS_INPUT_INDEX = 1;

	private final CppDecompilerHints renderer;
	private final CppTypeSystem typeSystem;

	/**
	 * Constructs a driver over the given renderer and type-system model.
	 *
	 * @param renderer the hint renderer to dispatch to; must not be null
	 * @param typeSystem the model resolving constructed classes to {@link CppClass}es; must not be null
	 * @throws IllegalArgumentException if either argument is null
	 */
	public CppPlacementConstructionDriver(CppDecompilerHints renderer, CppTypeSystem typeSystem) {
		if (renderer == null) {
			throw new IllegalArgumentException("renderer must not be null");
		}
		if (typeSystem == null) {
			throw new IllegalArgumentException("type system must not be null");
		}
		this.renderer = renderer;
		this.typeSystem = typeSystem;
	}

	/**
	 * Recognises every non-elided placement-{@code new} construction in the function and renders a hint
	 * for each one whose constructor resolves to a {@code name == class} constructor of a modelled
	 * class, whose allocation resolves to {@code operator new}, and whose placement buffer has a
	 * printable name.
	 *
	 * @param function the decompiled high function to walk; must not be null
	 * @return the rendered hints in p-code iteration order; empty if none recognised or resolved
	 * @throws IllegalArgumentException if {@code function} is null
	 */
	public List<RenderedPlacement> recognizeAndRender(HighFunction function) {
		if (function == null) {
			throw new IllegalArgumentException("high function must not be null");
		}
		Function host = function.getFunction();
		if (host == null) {
			return List.of();
		}
		Program program = host.getProgram();
		if (program == null) {
			return List.of();
		}
		FunctionManager functionManager = program.getFunctionManager();
		List<RenderedPlacement> rendered = new ArrayList<>();
		Iterator<PcodeOpAST> ops = function.getPcodeOps();
		while (ops.hasNext()) {
			PcodeOpAST op = ops.next();
			if (op.getOpcode() != PcodeOp.CALL) {
				continue;
			}
			PlacementConstruction object = CppPlacementConstructionRecognizer.recognize(op);
			if (object == null) {
				continue;
			}
			RenderedPlacement hint = render(op, object, functionManager);
			if (hint != null) {
				rendered.add(hint);
			}
		}
		return rendered;
	}

	private RenderedPlacement render(PcodeOp callSite, PlacementConstruction object,
			FunctionManager functionManager) {
		Function constructor = functionManager.getFunctionAt(object.constructorTarget());
		if (constructor == null) {
			return null;
		}
		String className = constructorClassName(constructor);
		if (className == null) {
			return null;
		}
		Function allocation = functionManager.getFunctionAt(object.allocationTarget());
		if (allocation == null || !isOperatorNew(allocation.getName())) {
			return null;
		}
		String placementExpr = operandName(object.placementBuffer());
		if (placementExpr == null) {
			return null;
		}
		CppClass type = typeSystem.getCppClass(className);
		if (type == null) {
			return null;
		}
		List<String> argumentExprs = constructorArguments(callSite);
		if (argumentExprs == null) {
			return null;
		}
		String rendering =
			renderer.renderPlacementConstruction(type, placementExpr, argumentExprs);
		return new RenderedPlacement(callSite.getSeqnum().getTarget(), rendering);
	}

	/**
	 * Recovers the explicit constructor arguments to render between the {@code ClassName(...)}
	 * parentheses. In the constructor {@code CALL}, input 0 is the call target and input 1 is the
	 * {@code this} receiver (the placement buffer); the explicit arguments are every input after that,
	 * in order &mdash; grounded against a decompiled placement {@code new (buf) C(arg)} whose
	 * constructor {@code CALL} carries the argument as its third input.
	 *
	 * <p>Each argument is rendered as its {@link HighVariable} name, the same operand rendering the
	 * buffer/receiver uses. An argument with no printable name (an unnamed temporary, or a bare
	 * constant, which carries no {@code HighVariable}) declines the whole hint ({@code null}) rather
	 * than rendering a constructor call with a gap in its argument list &mdash; the same advisory,
	 * never-wrong contract the receiver rendering holds. Rendering constants and compound expressions
	 * is later {@code #37-10} work.
	 *
	 * @return the rendered argument expressions in call order (empty for a no-argument constructor), or
	 *         null if any argument has no printable operand name
	 */
	private static List<String> constructorArguments(PcodeOp constructorCall) {
		List<String> arguments = new ArrayList<>();
		for (int i = THIS_INPUT_INDEX + 1; i < constructorCall.getNumInputs(); i++) {
			String argument = argumentExpr(constructorCall.getInput(i));
			if (argument == null) {
				return null;
			}
			arguments.add(argument);
		}
		return arguments;
	}

	/**
	 * Renders one constructor-argument varnode as a C++ expression: a named {@link HighVariable}'s name
	 * (e.g. {@code param_1}), a {@code bool} constant as {@code true}/{@code false} ({@code #37-10e}), a
	 * {@code char} constant as a character literal (e.g. {@code 'A'}, {@code #37-10f}), an {@code enum}
	 * constant as its qualified member name (e.g. {@code Color::GREEN}, {@code #37-10g}), or an
	 * integer-typed constant's decimal value (e.g. {@code 5}, or {@code -1} for a negative argument), so a
	 * literal argument like {@code new (buf) C(5)} renders rather than declining.
	 *
	 * <p>A constant is rendered only when its {@link HighVariable} datatype is a {@link BooleanDataType},
	 * a {@link CharDataType}, an {@link Enum}, or an {@link AbstractIntegerDataType}: a {@code bool}
	 * constant of {@code 0}/{@code 1} renders {@code false}/{@code true} (an out-of-range {@code bool}
	 * value falls through to its decimal, staying never-wrong); a {@code char} constant renders as an
	 * escaped C character literal ({@link #charConstantLiteral}); a wide-char constant renders as a
	 * prefixed wide-character literal ({@code L'A'}/{@code u'A'}/{@code U'A'} via
	 * {@link #wideCharConstantLiteral}); a floating-point constant renders as a decimal literal
	 * ({@code 2.5f}/{@code 2.5} via {@link #floatConstantLiteral}, declining a non-finite or exotic-width
	 * value); an {@code enum} constant renders as its
	 * qualified member name ({@link #enumConstantLiteral}, declining when the value names no member); and
	 * an integer literal's decimal value (the array driver's element count is rendered the same way) is a
	 * faithful hint, whereas a pointer-typed constant (e.g. a global string address) rendered as a bare
	 * decimal would mislead, so it is declined. The integer literal is read at the varnode's own byte
	 * width ({@link #integerConstantLiteral}) &mdash; sign-extended for a signed type, full-range for an
	 * unsigned one &mdash; so a negative or wide-unsigned argument stays faithful ({@code #37-10d}). The
	 * {@code char} branch precedes the integer branch because {@code CharDataType} (and {@code bool}) are
	 * themselves {@code AbstractIntegerDataType}s; the more specific type wins ({@code Enum} is not an
	 * {@code AbstractIntegerDataType}, so its order is immaterial). An argument that is neither named nor a
	 * boolean/char/enum/integer constant (an unnamed non-constant temporary, or a non-integer constant)
	 * declines the whole hint ({@code null}). Rendering compound expressions is later {@code #37-10} work.
	 *
	 * @return the rendered argument expression, or null if it is neither a named variable nor a
	 *         boolean/char/enum/integer-typed constant
	 */
	private static String argumentExpr(Varnode varnode) {
		String name = operandName(varnode);
		if (name != null) {
			return name;
		}
		if (varnode.isConstant()) {
			HighVariable high = varnode.getHigh();
			DataType type = high == null ? null : high.getDataType();
			if (type instanceof BooleanDataType) {
				long value = varnode.getOffset();
				if (value == 0 || value == 1) {
					return value == 0 ? "false" : "true";
				}
			}
			if (type instanceof CharDataType) {
				return charConstantLiteral(varnode);
			}
			if (type instanceof WideChar16DataType) {
				return wideCharConstantLiteral(varnode, "u");
			}
			if (type instanceof WideChar32DataType) {
				return wideCharConstantLiteral(varnode, "U");
			}
			if (type instanceof WideCharDataType) {
				return wideCharConstantLiteral(varnode, "L");
			}
			if (type instanceof AbstractFloatDataType) {
				return floatConstantLiteral(varnode);
			}
			if (type instanceof Enum enumType) {
				return enumConstantLiteral(varnode, enumType);
			}
			if (type instanceof AbstractIntegerDataType integerType) {
				return integerConstantLiteral(varnode, integerType);
			}
		}
		return null;
	}

	/**
	 * {@return the faithful decimal text of an integer-typed constant varnode, read at the varnode's
	 * own byte width}
	 *
	 * <p>A constant varnode's {@link Varnode#getOffset()} carries only the low {@code size * 8} value
	 * bits, zero-filled above; {@code Long.toString} of that raw long is therefore correct only for a
	 * signed size-8 value or a small unsigned one. A <em>signed</em> type's value is sign-extended from
	 * the varnode width (so a size-4 signed {@code int} constant {@code 0xffffffff} renders {@code -1},
	 * not {@code 4294967295}), and an <em>unsigned</em> type's value is rendered across the full unsigned
	 * range (so a size-8 {@code unsigned long long} with the high bit set renders its large positive
	 * value, not a negative one). This keeps the {@code #37-10c} integer-constant rendering never-wrong
	 * for negative and wide-unsigned arguments alike ({@code #37-10d}). (Duplicated from the heap driver
	 * as an honest per-form twin, per the DD-0026 rule-of-three convention, until a third user earns the
	 * extraction.)
	 */
	private static String integerConstantLiteral(Varnode varnode, AbstractIntegerDataType type) {
		long raw = varnode.getOffset();
		int bits = varnode.getSize() * 8;
		if (bits <= 0 || bits >= Long.SIZE) {
			return type.isSigned() ? Long.toString(raw) : Long.toUnsignedString(raw);
		}
		if (type.isSigned()) {
			return Long.toString((raw << (Long.SIZE - bits)) >> (Long.SIZE - bits));
		}
		return Long.toUnsignedString(raw & ((1L << bits) - 1));
	}

	/**
	 * {@return the C character-literal text of a {@link CharDataType} constant varnode (e.g.
	 * {@code 'A'}), with control and special characters escaped}
	 *
	 * <p>A {@code char} constant carries its byte value in the low 8 bits of the varnode offset (so
	 * {@code 'A'} is {@code 0x41}); rendered through the integer branch it would misleadingly print as
	 * the decimal {@code 65}. The byte is rendered as a single-quoted C character literal: a printable
	 * ASCII byte ({@code 0x20}&ndash;{@code 0x7e}) directly, the standard C escapes for the common
	 * control characters and for the quote and backslash, and a {@code \\xNN} hex escape for any other
	 * non-printable byte. Every byte therefore renders to a faithful, compilable literal, so a {@code char}
	 * constant never declines ({@code #37-10f}). {@code SignedCharDataType} and {@code UnsignedCharDataType}
	 * both extend {@link CharDataType}, so all three 1-byte char types render here; the wide-char types are
	 * not {@code CharDataType} and are rendered by {@link #wideCharConstantLiteral} ({@code #37-10h}).
	 * (Duplicated from the heap driver as an honest per-form twin, per the DD-0026 rule-of-three
	 * convention, until a third user earns the extraction.)
	 */
	private static String charConstantLiteral(Varnode varnode) {
		int value = (int) (varnode.getOffset() & 0xff);
		String body = switch (value) {
			case '\0' -> "\\0";
			case 0x07 -> "\\a";
			case '\b' -> "\\b";
			case '\t' -> "\\t";
			case '\n' -> "\\n";
			case 0x0b -> "\\v";
			case '\f' -> "\\f";
			case '\r' -> "\\r";
			case '\'' -> "\\'";
			case '\\' -> "\\\\";
			default -> (value >= 0x20 && value <= 0x7e) ? String.valueOf((char) value)
					: String.format("\\x%02x", value);
		};
		return "'" + body + "'";
	}

	/**
	 * {@return the prefixed C++ wide-character-literal text of a wide-char constant varnode (e.g.
	 * {@code L'A'}, {@code u'A'}, {@code U'A'}), with control and special characters escaped}
	 *
	 * <p>{@link WideCharDataType} ({@code wchar_t}), {@link WideChar16DataType} ({@code char16_t}), and
	 * {@link WideChar32DataType} ({@code char32_t}) extend {@code BuiltIn}, not
	 * {@link AbstractIntegerDataType}, so a wide-char constant reached none of the integer/char branches
	 * and declined; rendered through the integer branch it would have printed a bare decimal code point.
	 * The value is read at the varnode's own byte width (the ground-truth width of the constant &mdash;
	 * {@code wchar_t} is 2 bytes on MSVC and 4 on the Itanium ABI, so the declared type length is not
	 * relied on) and emitted as a {@code prefix}-tagged single-quoted literal: a printable ASCII code unit
	 * directly ({@code L'A'}), the standard C escapes for the common control characters and for the quote
	 * and backslash, and a width-padded {@code \\x...} hex escape ({@code \\x20ac} for a 2-byte unit,
	 * {@code \\x0001f600} for a 4-byte one) for any other value &mdash; valid in a wide-character literal
	 * and free of the {@code \\u}/{@code \\U} universal-character-name restrictions (control and surrogate
	 * code points). Every value therefore renders to a faithful, compilable literal, so a wide-char
	 * constant never declines ({@code #37-10h}). The caller passes {@code "L"}, {@code "u"}, or {@code "U"}
	 * for {@code wchar_t}/{@code char16_t}/{@code char32_t} respectively. (Duplicated from the heap driver
	 * as an honest per-form twin, per the DD-0026 rule-of-three convention, until a third user earns the
	 * extraction.)
	 */
	private static String wideCharConstantLiteral(Varnode varnode, String prefix) {
		int size = varnode.getSize();
		int bits = size * 8;
		long value = (bits <= 0 || bits >= Long.SIZE) ? varnode.getOffset()
				: varnode.getOffset() & ((1L << bits) - 1);
		int hexDigits = (size >= 1 && size <= 8) ? size * 2 : 8;
		String body = switch ((int) value) {
			case '\0' -> "\\0";
			case 0x07 -> "\\a";
			case '\b' -> "\\b";
			case '\t' -> "\\t";
			case '\n' -> "\\n";
			case 0x0b -> "\\v";
			case '\f' -> "\\f";
			case '\r' -> "\\r";
			case '\'' -> "\\'";
			case '\\' -> "\\\\";
			default -> (value >= 0x20 && value <= 0x7e) ? String.valueOf((char) value)
					: String.format("\\x%0" + hexDigits + "x", value);
		};
		return prefix + "'" + body + "'";
	}

	/**
	 * {@return the C++ floating-point-literal text of a {@link AbstractFloatDataType} constant varnode
	 * (e.g. {@code 2.5f} for a {@code float}, {@code 2.5} for a {@code double}), or null when the width is
	 * not 4/8 bytes or the value is not finite}
	 *
	 * <p>{@code FloatDataType}/{@code DoubleDataType} extend {@code AbstractFloatDataType} (in turn
	 * {@code BuiltIn}), not {@link AbstractIntegerDataType}, so a float constant reached none of the
	 * integer branches and declined. A float constructor argument arrives as a constant varnode whose
	 * offset carries the IEEE-754 bit pattern (grounded: a {@code 2.5f} argument is a size-4 constant with
	 * offset {@code 0x40200000}); the bits are decoded at the varnode width &mdash; {@code size 4} via
	 * {@link Float#intBitsToFloat(int)} and {@code size 8} via {@link Double#longBitsToDouble(long)}
	 * &mdash; and rendered with {@link Float#toString(float)}/{@link Double#toString(double)}, which emit
	 * the shortest round-tripping decimal. A {@code float} gets the {@code f} suffix so the literal keeps
	 * its single-precision type; a {@code double} is the unsuffixed default. A non-finite value
	 * ({@code NaN}/{@code Infinity}) has no bare C++ literal, so it declines rather than emit the invalid
	 * {@code NaN}/{@code Infinity} text; exotic widths (half, x87 80-bit extended, quad) also decline in
	 * this slice ({@code #37-10i}). (Duplicated from the heap driver as an honest per-form twin, per
	 * the DD-0026 rule-of-three convention, until a third user earns the extraction.)
	 */
	private static String floatConstantLiteral(Varnode varnode) {
		int size = varnode.getSize();
		long bits = varnode.getOffset();
		if (size == 4) {
			float value = Float.intBitsToFloat((int) bits);
			return Float.isFinite(value) ? Float.toString(value) + "f" : null;
		}
		if (size == 8) {
			double value = Double.longBitsToDouble(bits);
			return Double.isFinite(value) ? Double.toString(value) : null;
		}
		return null;
	}

	/**
	 * {@return the qualified member-name text of an {@link Enum} constant varnode (e.g.
	 * {@code Color::GREEN}), or null when the value names no member}
	 *
	 * <p>An {@code enum} constant carries its underlying integer value in the low {@code size * 8} bits
	 * of the varnode offset; rendered as a bare decimal it would lose the symbolic member name and, for a
	 * scoped {@code enum class}, would not even be valid C++. The value is read at the varnode's byte
	 * width &mdash; sign-extended when the enum is signed (so a negative member matches), masked otherwise
	 * &mdash; and looked up via {@link Enum#getName(long)}. A matched member is rendered qualified by the
	 * enum's type name ({@code Color::GREEN}), which is valid C++ for both scoped and unscoped enums; if
	 * the type name is blank the bare member name is used. When the value names no member
	 * ({@code getName} returns null &mdash; e.g. a flag combination or an out-of-range value) the hint
	 * declines rather than fabricate a name or a bare number that would mislead, keeping the never-wrong
	 * contract ({@code #37-10g}). {@code EnumDataType}/{@code EnumDB} are not
	 * {@link AbstractIntegerDataType}s, so an enum constant reaches this branch rather than the integer
	 * one. (Duplicated from the heap driver as an honest per-form twin, per the DD-0026 rule-of-three
	 * convention, until a third user earns the extraction.)
	 */
	private static String enumConstantLiteral(Varnode varnode, Enum enumType) {
		long raw = varnode.getOffset();
		int bits = varnode.getSize() * 8;
		long value;
		if (bits <= 0 || bits >= Long.SIZE) {
			value = raw;
		}
		else if (enumType.isSigned()) {
			value = (raw << (Long.SIZE - bits)) >> (Long.SIZE - bits);
		}
		else {
			value = raw & ((1L << bits) - 1);
		}
		String member = enumType.getName(value);
		if (member == null || member.isBlank()) {
			return null;
		}
		String typeName = enumType.getName();
		return (typeName == null || typeName.isBlank()) ? member : typeName + "::" + member;
	}

	/**
	 * {@return the printable source name of a varnode's {@link HighVariable} (e.g. {@code param_1}), or
	 * null if it has no real name}
	 *
	 * <p>A varnode with no backing symbol is a {@code HighOther}, whose {@link HighVariable#getName()}
	 * returns the sentinel {@code "UNNAMED"} (set in {@code HighOther} unless a symbol resolves it). That
	 * sentinel is not a source name: a string-pointer or compound-expression argument that the decompiler
	 * could not name reaches the call as such an {@code UNNAMED} {@code HighOther}, and rendering its name
	 * verbatim would emit the misleading {@code new (param_1) C(UNNAMED)}. Treating {@code "UNNAMED"} (and a
	 * null/blank name) as no-name makes the whole hint decline instead, keeping the never-wrong contract
	 * ({@code #37-10j}); rendering the underlying string literal / compound expression is later
	 * {@code #37-10} work. (Duplicated from the heap driver as an honest per-form twin, per the DD-0026
	 * rule-of-three convention, until a third user earns the extraction.)
	 */
	private static String operandName(Varnode varnode) {
		HighVariable high = varnode.getHigh();
		if (high == null) {
			return null;
		}
		String name = high.getName();
		if (name == null || name.isBlank() || name.equals("UNNAMED")) {
			return null;
		}
		return name;
	}

	/**
	 * {@return the class name a constructor callee names — its local name, which a demangled
	 * constructor shares with its parent (class) namespace — or null if the function is not a
	 * {@code name == class} constructor (no parent namespace, or a name unequal to it)}
	 */
	private static String constructorClassName(Function constructor) {
		String name = constructor.getName();
		Namespace parent = constructor.getParentNamespace();
		if (name == null || parent == null) {
			return null;
		}
		String parentName = parent.getName();
		if (parentName == null || !name.equals(parentName)) {
			return null;
		}
		return name;
	}

	/**
	 * {@return whether the callee name denotes {@code operator new} — matched in the demangled form
	 * Ghidra emits (its {@code .} namespace separator normalised to a space), so {@code operator.new}
	 * and the plain {@code operator new} form compare equal}
	 */
	private static boolean isOperatorNew(String calleeName) {
		if (calleeName == null) {
			return false;
		}
		return calleeName.replace('.', ' ').equals("operator new");
	}
}
