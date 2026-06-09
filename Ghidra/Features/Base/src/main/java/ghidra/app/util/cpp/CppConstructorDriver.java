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

import ghidra.app.util.cpp.CppConstructorRecognizer.ConstructedObject;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressOutOfBoundsException;
import ghidra.program.model.data.AbstractFloatDataType;
import ghidra.program.model.data.AbstractIntegerDataType;
import ghidra.program.model.data.BooleanDataType;
import ghidra.program.model.data.CharDataType;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.Enum;
import ghidra.program.model.data.Pointer;
import ghidra.program.model.data.WideChar16DataType;
import ghidra.program.model.data.WideChar32DataType;
import ghidra.program.model.data.WideCharDataType;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryAccessException;
import ghidra.program.model.pcode.HighFunction;
import ghidra.program.model.pcode.HighVariable;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.pcode.PcodeOpAST;
import ghidra.program.model.pcode.Varnode;
import ghidra.program.model.symbol.Namespace;

/**
 * The driver half of Rec 37 {@code #37-9b}: walks a decompiled {@link HighFunction}, uses the
 * {@link CppConstructorRecognizer} fusion matcher ({@code #37-9b-1}, DD-0030) to find candidate
 * heap-{@code new} constructions, resolves the recovered constructor and allocation targets to
 * {@link Function}s, confirms the first is a constructor and the second is {@code operator new},
 * resolves the constructed {@link CppClass} in a supplied {@link CppTypeSystem}, and dispatches to
 * the stateless {@link CppDecompilerHints#renderConstruction} renderer (DD-0016) to produce the C++
 * hint string. This is the {@code #37-9b-2} slice that closes the loop the recognizer opened.
 *
 * <p><b>Two callee classifications, both from names.</b> The matcher recovered two addresses but, as
 * a p-code-only pass, could not decide what the callees <em>are</em>. The driver holds the
 * {@link Program} (via {@code function.getFunction().getProgram()}) and classifies each:
 * <ul>
 * <li><b>The constructor, and its class.</b> A constructor's demangled Ghidra local name <em>equals
 * its class</em> — the function {@code getName()} equals its parent (class) namespace name
 * (e.g. {@code _ZN3Bar4FredC1Ei} demangles to {@code Bar::Fred::Fred(int)}, local name {@code Fred}
 * in namespace {@code Fred}). The driver resolves {@link ConstructedObject#constructorTarget()} to a
 * {@link Function}, requires its name to equal its parent namespace name, and looks that class up in
 * the type system. This {@code name == class} marker is the constructor's counterpart to the
 * destructor's {@code ~} prefix; a member method ({@code Fred::foo}) has a name unequal to its class
 * and is declined.</li>
 * <li><b>The allocation as {@code operator new}.</b> The driver resolves
 * {@link ConstructedObject#allocationTarget()} and requires its name (in the demangled form Ghidra
 * emits, its {@code .} namespace separator) to be {@code operator new} — normalised the same way the
 * {@code #37-9f-b} delete driver (DD-0027) matches {@code operator delete}. A construction on storage
 * from any other call (a placement {@code new}, a custom allocator) is declined here; the placement
 * form is the separate {@code #37-9e-b} slice.</li>
 * </ul>
 *
 * <p>The renderer emits {@code new ClassName(args)} and takes <em>no receiver</em>: a heap {@code new}
 * is the allocation-plus-construction, with no printed {@code this}. <b>Explicit constructor arguments
 * are threaded</b> ({@code #37-10b}&ndash;{@code #37-10e}): the constructor {@code CALL}'s inputs after the
 * call target (index 0) and the {@code this} receiver (index 1) are its explicit arguments. A named
 * argument renders as its {@link HighVariable} name, a {@code bool} constant as {@code true}/{@code false},
 * and an integer-typed constant as its decimal value (read at the varnode's byte width, so a negative
 * argument renders {@code -1}, not a large unsigned number), so {@code new ClassName(arg)},
 * {@code new ClassName(5)}, and {@code new ClassName(true)} all render; a zero-argument
 * constructor still renders {@code new ClassName()}. An argument that is neither named nor an integer
 * constant (an unnamed non-constant temporary, or a non-integer constant such as a pointer-typed global
 * address) declines the whole hint rather than rendering a gap or a misleading bare number &mdash; the
 * same advisory, never-wrong contract the {@code #37-10a} {@link CppPlacementConstructionDriver} holds.
 * (The argument helpers are duplicated from the placement driver rather than extracted: they are kept as
 * honest per-form twins, per the DD-0026 rule-of-three convention, until a third user earns the
 * extraction.) A one-level compound argument also renders &mdash; a two-operand arithmetic, bitwise, or
 * shift expression over leaf operands such as {@code new C(param_1 + 7)} ({@code #37-10m},
 * {@link #binaryExpr}). Nested compounds, division/remainder/comparison/unary operators, and overload
 * resolution against the {@code DataType} signature are later {@code #37-10} work.
 *
 * <p>The pass therefore assumes the demangler analyzer has run — which it has by the time a function
 * decompiles to a {@code HighFunction} in a fully-analyzed program. A not-yet-demangled mangled
 * constructor or {@code operator new} symbol would be declined.
 *
 * <p><b>Advisory, never wrong.</b> Like the matcher and renderer it sits between, the driver is
 * additive and total-failure-safe: a construction whose constructor target resolves to no function or
 * is not a {@code name == class} constructor, whose allocation target is not {@code operator new}, or
 * whose class is not modelled in the type system is silently skipped (it contributes no hint), never
 * mis-rendered or raised as an error. A function with no recognised construction yields an empty list.
 */
public final class CppConstructorDriver {

	/**
	 * A rendered heap-construction hint: the {@code site} address of the constructor {@code CALL} it
	 * was recovered from, and the {@code rendering} string the {@link CppDecompilerHints} renderer
	 * produced.
	 *
	 * @param site the address of the dispatching constructor call op
	 * @param rendering the rendered C++ {@code new} expression
	 */
	public record RenderedConstruction(Address site, String rendering) {}

	/** A constructor {@code CALL}'s input 0 is the call target; input 1 is the {@code this} receiver. */
	private static final int THIS_INPUT_INDEX = 1;

	/** A string-pointer argument's def-chain is traced through at most this many COPY/CAST pass-throughs. */
	private static final int MAX_STRING_DEF_HOPS = 4;

	/** A traced string is read up to this many bytes before declining a missing NUL terminator. */
	private static final int MAX_STRING_LENGTH = 4096;

	private final CppDecompilerHints renderer;
	private final CppTypeSystem typeSystem;

	/**
	 * Constructs a driver over the given renderer and type-system model.
	 *
	 * @param renderer the hint renderer to dispatch to; must not be null
	 * @param typeSystem the model resolving constructed classes to {@link CppClass}es; must not be null
	 * @throws IllegalArgumentException if either argument is null
	 */
	public CppConstructorDriver(CppDecompilerHints renderer, CppTypeSystem typeSystem) {
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
	 * Recognises every heap-{@code new} construction in the function and renders a hint for each one
	 * whose constructor resolves to a {@code name == class} constructor of a modelled class and whose
	 * allocation resolves to {@code operator new}.
	 *
	 * @param function the decompiled high function to walk; must not be null
	 * @return the rendered hints in p-code iteration order; empty if none recognised or resolved
	 * @throws IllegalArgumentException if {@code function} is null
	 */
	public List<RenderedConstruction> recognizeAndRender(HighFunction function) {
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
		List<RenderedConstruction> rendered = new ArrayList<>();
		Iterator<PcodeOpAST> ops = function.getPcodeOps();
		while (ops.hasNext()) {
			PcodeOpAST op = ops.next();
			if (op.getOpcode() != PcodeOp.CALL) {
				continue;
			}
			ConstructedObject object = CppConstructorRecognizer.recognize(op);
			if (object == null) {
				continue;
			}
			RenderedConstruction hint = render(op, object, functionManager, program);
			if (hint != null) {
				rendered.add(hint);
			}
		}
		return rendered;
	}

	private RenderedConstruction render(PcodeOp callSite, ConstructedObject object,
			FunctionManager functionManager, Program program) {
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
		CppClass type = typeSystem.getCppClass(className);
		if (type == null) {
			return null;
		}
		List<String> argumentExprs = constructorArguments(callSite, program);
		if (argumentExprs == null) {
			return null;
		}
		String rendering = renderer.renderConstruction(type, argumentExprs);
		return new RenderedConstruction(callSite.getSeqnum().getTarget(), rendering);
	}

	/**
	 * Recovers the explicit constructor arguments to render between the {@code ClassName(...)}
	 * parentheses. In the constructor {@code CALL}, input 0 is the call target and input 1 is the
	 * {@code this} receiver (the allocated storage); the explicit arguments are every input after that,
	 * in order &mdash; the same layout the {@code #37-10a} placement driver threads.
	 *
	 * <p>Each argument is rendered as its {@link HighVariable} name, the same operand rendering the
	 * receiver forms use. An argument with no printable name (an unnamed temporary, or a bare constant,
	 * which carries no {@code HighVariable}) declines the whole hint ({@code null}) rather than rendering
	 * a constructor call with a gap in its argument list. The {@code program} is threaded through so a
	 * {@code const char*} string-pointer argument can be traced to its global address and read as a string
	 * literal ({@code #37-10k}), and a one-level compound argument renders as a binary expression
	 * ({@code #37-10m}).
	 *
	 * @return the rendered argument expressions in call order (empty for a no-argument constructor), or
	 *         null if any argument has no printable operand name
	 */
	private static List<String> constructorArguments(PcodeOp constructorCall, Program program) {
		List<String> arguments = new ArrayList<>();
		for (int i = THIS_INPUT_INDEX + 1; i < constructorCall.getNumInputs(); i++) {
			String argument = argumentExpr(constructorCall.getInput(i), program);
			if (argument == null) {
				return null;
			}
			arguments.add(argument);
		}
		return arguments;
	}

	/**
	 * Renders one constructor-argument varnode as a C++ expression. An argument is first tried as a
	 * <em>leaf</em> ({@link #leafExpr}) &mdash; a named variable, a string literal, or a typed constant
	 * &mdash; and, failing that, as a two-operand <em>binary expression</em> ({@link #binaryExpr}) such as
	 * {@code param_1 + 7} ({@code #37-10m}). An argument that is neither declines the whole hint
	 * ({@code null}), keeping the never-wrong contract.
	 *
	 * @return the rendered argument expression, or null if it is neither a renderable leaf nor a
	 *         renderable binary expression
	 */
	private static String argumentExpr(Varnode varnode, Program program) {
		String leaf = leafExpr(varnode, program);
		if (leaf != null) {
			return leaf;
		}
		return binaryExpr(varnode, program);
	}

	/**
	 * Renders one <em>leaf</em> constructor-argument varnode as a C++ expression: a named
	 * {@link HighVariable}'s name (e.g. {@code param_1}), a {@code bool} constant as
	 * {@code true}/{@code false} ({@code #37-10e}), a {@code char} constant as a character literal (e.g.
	 * {@code 'A'}, {@code #37-10f}), an {@code enum} constant as its qualified member name (e.g.
	 * {@code Color::GREEN}, {@code #37-10g}), or an integer-typed constant's decimal value (e.g. {@code 5},
	 * or {@code -1} for a negative argument), so a literal argument like {@code new C(5)} renders rather
	 * than declining. A leaf is a single named variable, string literal, or constant &mdash; it carries no
	 * operator; a compound operand is rendered by {@link #binaryExpr}.
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
	 * an integer literal's decimal value is a faithful hint, whereas a pointer-typed constant (e.g. a
	 * global string address) rendered as a bare decimal would mislead, so it is declined. The integer
	 * literal is read at the varnode's own byte width ({@link #integerConstantLiteral}) &mdash;
	 * sign-extended for a signed type, full-range for an unsigned one &mdash; so a negative or
	 * wide-unsigned argument stays faithful ({@code #37-10d}). The {@code char} branch precedes the integer
	 * branch because {@code CharDataType} (and {@code bool}) are themselves {@code AbstractIntegerDataType}s;
	 * the more specific type wins ({@code Enum} is not an {@code AbstractIntegerDataType}, so its order is
	 * immaterial).
	 *
	 * <p>A narrow or wide string-pointer argument is tried before the {@code isConstant} constant
	 * branches ({@link #stringConstantLiteral}): such an argument is <em>not</em> a constant varnode but an
	 * unnamed {@code char *}/{@code wchar_t *}/{@code char16_t *}/{@code char32_t *} temporary whose
	 * definition copies a global address, so it is traced to that address and read as a narrow
	 * {@code "..."} or prefixed wide {@code L"..."}/{@code u"..."}/{@code U"..."} string literal
	 * ({@code #37-10k}, {@code #37-10l}). A leaf that is neither named, a string pointer, nor a
	 * boolean/char/enum/integer constant (an unnamed non-constant temporary that is not a readable string
	 * pointer, or a non-integer constant) returns {@code null}, leaving the caller to try a binary
	 * expression. (Duplicated from the placement driver as an honest per-form twin, per the DD-0026
	 * rule-of-three convention, until a third user earns the extraction.)
	 *
	 * @return the rendered leaf expression, or null if it is neither a named variable, a narrow or wide
	 *         string pointer, nor a boolean/char/enum/integer-typed constant
	 */
	private static String leafExpr(Varnode varnode, Program program) {
		String name = operandName(varnode);
		if (name != null) {
			return name;
		}
		String stringLiteral = stringConstantLiteral(varnode, program);
		if (stringLiteral != null) {
			return stringLiteral;
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
	 * {@return the rendered C++ binary expression for a compound constructor argument (e.g.
	 * {@code param_1 + 7}, {@code param_1 << 3}), or null when the argument is not a recognised two-operand
	 * binary expression over two renderable leaves}
	 *
	 * <p>A compound argument is <em>not</em> named and is not a constant: the decompiler reaches the
	 * {@code CALL} with an unnamed temporary whose {@link Varnode#getDef() definition} is the arithmetic,
	 * bitwise, or shift p-code op that computed it (grounded: {@code new C(v + 7)} arrives as an
	 * {@code UNNAMED} {@code HighOther} defined by {@code INT_ADD} of the named {@code param_1} and the
	 * constant {@code 7}). This helper renders that op as {@code left OP right} where {@code OP} is the
	 * C++ glyph for the opcode ({@link #binaryOperator}) and each operand is a <em>leaf</em>
	 * ({@link #leafExpr}). Both operands are rendered as leaves only &mdash; a leaf carries no operator, so
	 * the single-operator result needs no parentheses and is never precedence-ambiguous &mdash; and an
	 * operand that is itself a compound (or any unnameable temporary) makes {@code leafExpr} decline, so
	 * the whole hint declines rather than guess at nesting or parenthesisation. This keeps the never-wrong
	 * contract while rendering the common one-level compound shapes ({@code #37-10m}).
	 *
	 * <p>Only a two-input op is considered, and only one whose opcode {@link #binaryOperator} maps to a
	 * glyph; any other definition declines. A deliberately declined case is a <em>logical</em> right shift
	 * ({@code INT_RIGHT}) whose left operand the decompiler wrapped in a {@code CAST} to an unsigned type:
	 * peeling that cast and rendering a bare {@code param_1 >> 3} over a signed operand would silently
	 * become an <em>arithmetic</em> shift, so the cast-wrapped operand is not a leaf, {@code leafExpr}
	 * declines it, and the hint declines &mdash; faithful over complete. An <em>arithmetic</em> right shift
	 * ({@code INT_SRIGHT}) carries its operand directly and renders. (Duplicated from the placement driver
	 * as an honest per-form twin, per the DD-0026 rule-of-three convention, until a third user earns the
	 * extraction.)
	 */
	private static String binaryExpr(Varnode varnode, Program program) {
		PcodeOp def = varnode.getDef();
		if (def == null || def.getNumInputs() != 2) {
			return null;
		}
		String operator = binaryOperator(def.getOpcode());
		if (operator == null) {
			return null;
		}
		String left = leafExpr(def.getInput(0), program);
		if (left == null) {
			return null;
		}
		String right = leafExpr(def.getInput(1), program);
		if (right == null) {
			return null;
		}
		return left + " " + operator + " " + right;
	}

	/**
	 * {@return the C++ operator glyph for a binary integer p-code opcode (e.g. {@code +} for
	 * {@code INT_ADD}, {@code >>} for {@code INT_RIGHT}/{@code INT_SRIGHT}), or null when the opcode is not
	 * one of the recognised arithmetic, bitwise, or shift operators}
	 *
	 * <p>The mapping is grounded over the opcodes the decompiler emits for the corresponding C operations
	 * ({@code #37-10m}, {@code #37-10n}): {@code INT_ADD}/{@code INT_SUB}/{@code INT_MULT} for {@code + - *},
	 * {@code INT_AND}/{@code INT_OR}/{@code INT_XOR} for {@code & | ^}, {@code INT_LEFT} for {@code <<},
	 * both the logical {@code INT_RIGHT} and the arithmetic {@code INT_SRIGHT} for {@code >>}, both the
	 * signed {@code INT_SDIV} and the unsigned {@code INT_DIV} for {@code /}, and both the signed
	 * {@code INT_SREM} and the unsigned {@code INT_REM} for {@code %} (the source signedness, not the
	 * glyph, distinguishes the paired forms). For the shift, division, and remainder pairs the unsigned
	 * variant casts its left operand to an unsigned type, so {@link #binaryExpr}'s leaf-only operand rule
	 * declines it (a faithful render would silently change signedness); the signed variant carries its
	 * operand directly and renders. Comparison and unary operators are not yet mapped and decline.
	 * (Duplicated from the placement driver as an honest per-form twin, per the DD-0026 rule-of-three
	 * convention, until a third user earns the extraction.)
	 */
	private static String binaryOperator(int opcode) {
		return switch (opcode) {
			case PcodeOp.INT_ADD -> "+";
			case PcodeOp.INT_SUB -> "-";
			case PcodeOp.INT_MULT -> "*";
			case PcodeOp.INT_AND -> "&";
			case PcodeOp.INT_OR -> "|";
			case PcodeOp.INT_XOR -> "^";
			case PcodeOp.INT_LEFT -> "<<";
			case PcodeOp.INT_RIGHT, PcodeOp.INT_SRIGHT -> ">>";
			case PcodeOp.INT_SDIV, PcodeOp.INT_DIV -> "/";
			case PcodeOp.INT_SREM, PcodeOp.INT_REM -> "%";
			default -> null;
		};
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
	 * for negative and wide-unsigned arguments alike ({@code #37-10d}). (Duplicated from the placement
	 * driver as an honest per-form twin, per the DD-0026 rule-of-three convention, until a third user
	 * earns the extraction.)
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
	 * (Duplicated from the placement driver as an honest per-form twin, per the DD-0026 rule-of-three
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
	 * for {@code wchar_t}/{@code char16_t}/{@code char32_t} respectively. (Duplicated from the placement
	 * driver as an honest per-form twin, per the DD-0026 rule-of-three convention, until a third user earns
	 * the extraction.)
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
	 * this slice ({@code #37-10i}). (Duplicated from the placement driver as an honest per-form twin, per
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
	 * {@return the C++ string-literal text of a narrow or wide string-pointer argument (e.g.
	 * {@code "Hi"}, {@code L"Hi"}, {@code u"Hi"}, {@code U"Hi"}), traced from its global address and read
	 * as a zero-terminated code-unit sequence in program memory, or null when the argument is not a
	 * readable string pointer}
	 *
	 * <p>A string-pointer argument is <em>not</em> a constant varnode: the decompiler loads the global
	 * string address into an unnamed character-pointer temporary (grounded: a {@code HighOther} named
	 * {@code UNNAMED}, declined by {@link #operandName}), so it reaches none of the {@code isConstant}
	 * constant branches. This helper renders it by tracing the temporary's definition through up to
	 * {@link #MAX_STRING_DEF_HOPS} {@code COPY}/{@code CAST} single-input pass-throughs to the constant
	 * global address (grounded: a single {@code COPY} of a {@code const}-space varnode holding the
	 * address), forming that address in the program's default space, and reading the zero-terminated
	 * code units from program memory ({@link #readStringLiteral}).
	 *
	 * <p>It is gated on the argument's {@link HighVariable} datatype being a {@link Pointer} to a string
	 * character type ({@link #stringLiteralPrefix}): a {@link CharDataType} pointer renders the unprefixed
	 * narrow {@code "..."} (so {@code char*}/{@code signed char*}/{@code unsigned char*} all match, since
	 * both narrow-char subclasses extend {@link CharDataType}), and a {@link WideCharDataType} /
	 * {@link WideChar16DataType} / {@link WideChar32DataType} pointer renders the prefixed wide
	 * {@code L"..."} / {@code u"..."} / {@code U"..."} ({@code #37-10l}). The code-unit width is the
	 * pointee's own {@link DataType#getLength() length} (so {@code wchar_t} reads at its ground-truth
	 * 2-byte MSVC / 4-byte Itanium width rather than a hard-coded one); a non-string pointer, or a width
	 * other than 1/2/4, declines. A null pointer, an unreadable address ({@code MemoryAccessException} or
	 * out-of-bounds), a wide code unit that has no faithful literal form (a lone surrogate or a
	 * non-code-point value, see {@link #escapeStringUnit}), or a string with no terminator within
	 * {@link #MAX_STRING_LENGTH} code units declines ({@code null}), keeping the never-wrong contract
	 * ({@code #37-10k}, {@code #37-10l}). (Duplicated from the placement driver as an honest per-form
	 * twin, per the DD-0026 rule-of-three convention, until a third user earns the extraction.)
	 */
	private static String stringConstantLiteral(Varnode varnode, Program program) {
		if (program == null) {
			return null;
		}
		HighVariable high = varnode.getHigh();
		if (high == null) {
			return null;
		}
		if (!(high.getDataType() instanceof Pointer pointer)) {
			return null;
		}
		DataType pointee = pointer.getDataType();
		String prefix = stringLiteralPrefix(pointee);
		if (prefix == null) {
			return null;
		}
		int unitWidth = pointee.getLength();
		if (unitWidth != 1 && unitWidth != 2 && unitWidth != 4) {
			return null;
		}
		Varnode current = varnode;
		for (int hop = 0; hop <= MAX_STRING_DEF_HOPS && current != null; hop++) {
			if (current.isConstant()) {
				return readStringLiteral(program, current.getOffset(), prefix, unitWidth);
			}
			PcodeOp def = current.getDef();
			if (def == null) {
				return null;
			}
			int opcode = def.getOpcode();
			if (opcode != PcodeOp.COPY && opcode != PcodeOp.CAST) {
				return null;
			}
			current = def.getInput(0);
		}
		return null;
	}

	/**
	 * {@return the C++ string-literal prefix for a pointee character type &mdash; {@code ""} for
	 * {@link CharDataType} (narrow), {@code "u"} for {@link WideChar16DataType} ({@code char16_t}),
	 * {@code "U"} for {@link WideChar32DataType} ({@code char32_t}), and {@code "L"} for
	 * {@link WideCharDataType} ({@code wchar_t}) &mdash; or null when the pointee is not a string
	 * character type}
	 *
	 * <p>The four string-char types are unrelated by inheritance ({@link CharDataType} extends
	 * {@code AbstractIntegerDataType}; the three wide types each extend {@code BuiltIn}), so the
	 * {@code instanceof} order is immaterial. A {@code null} return (not an empty string) is the
	 * not-a-string signal; the empty-string narrow prefix is a successful match.
	 */
	private static String stringLiteralPrefix(DataType pointee) {
		if (pointee instanceof CharDataType) {
			return "";
		}
		if (pointee instanceof WideChar16DataType) {
			return "u";
		}
		if (pointee instanceof WideChar32DataType) {
			return "U";
		}
		if (pointee instanceof WideCharDataType) {
			return "L";
		}
		return null;
	}

	/**
	 * {@return the prefix-tagged double-quoted C++ string literal read from zero-terminated program memory
	 * at the given default-space address offset, reading code units of {@code unitWidth} bytes in the
	 * program's endian order, or null when the address is unreadable, a code unit has no faithful literal
	 * form, or there is no terminator within {@link #MAX_STRING_LENGTH} code units}
	 */
	private static String readStringLiteral(Program program, long addressOffset, String prefix,
			int unitWidth) {
		Memory memory = program.getMemory();
		Address base;
		try {
			base = program.getAddressFactory().getDefaultAddressSpace().getAddress(addressOffset);
		}
		catch (AddressOutOfBoundsException e) {
			return null;
		}
		StringBuilder body = new StringBuilder();
		for (int i = 0; i < MAX_STRING_LENGTH; i++) {
			long unit;
			try {
				Address at = base.add((long) i * unitWidth);
				unit = switch (unitWidth) {
					case 1 -> memory.getByte(at) & 0xffL;
					case 2 -> memory.getShort(at) & 0xffffL;
					default -> memory.getInt(at) & 0xffffffffL;
				};
			}
			catch (MemoryAccessException | AddressOutOfBoundsException e) {
				return null;
			}
			if (unit == 0) {
				return prefix + "\"" + body + "\"";
			}
			String fragment = escapeStringUnit(unit, unitWidth);
			if (fragment == null) {
				return null;
			}
			body.append(fragment);
		}
		return null;
	}

	/**
	 * {@return the C++ string-literal escaping of one code unit, or null when a wide code unit has no
	 * faithful literal form}
	 *
	 * <p>The standard C escapes are used for the common control characters and for {@code "} and
	 * {@code \}, and a printable ASCII unit ({@code 0x20}&ndash;{@code 0x7e}) renders directly. Any other
	 * control unit ({@code 0x00}&ndash;{@code 0x1f}, {@code 0x7f}) renders as a 3-digit octal
	 * {@code \\ooo} escape &mdash; <em>not</em> {@code \\xNN}, because a hex escape inside a string literal
	 * is greedy (it consumes every following hex digit, so {@code \\x7} before a literal {@code A} is
	 * misread as {@code \\x7A}), whereas the fixed-width 3-digit octal form ends after exactly three
	 * digits and any value {@code <= 0x7f} fits.
	 *
	 * <p>For a narrow ({@code unitWidth == 1}) string, the remaining high bytes ({@code 0x80}&ndash;{@code
	 * 0xff}) are raw bytes and also render as 3-digit octal. For a wide string, a high code unit
	 * ({@code >= 0x80}) is a Unicode code point and renders as a fixed-width universal-character-name
	 * &mdash; {@code \\uXXXX} (four hex digits) up to {@code 0xffff}, {@code \\UXXXXXXXX} (eight hex
	 * digits) above &mdash; which, like octal, is not greedy. A lone surrogate ({@code 0xd800}&ndash;{@code
	 * 0xdfff}) or a value beyond the Unicode range ({@code > 0x10ffff}) has no well-formed
	 * universal-character-name, so it declines ({@code null}) and the whole literal is abandoned, keeping
	 * the never-wrong contract.
	 */
	private static String escapeStringUnit(long unit, int unitWidth) {
		switch ((int) unit) {
			case 0x07:
				return "\\a";
			case '\b':
				return "\\b";
			case '\t':
				return "\\t";
			case '\n':
				return "\\n";
			case 0x0b:
				return "\\v";
			case '\f':
				return "\\f";
			case '\r':
				return "\\r";
			case '"':
				return "\\\"";
			case '\\':
				return "\\\\";
			default:
				break;
		}
		if (unit >= 0x20 && unit <= 0x7e) {
			return String.valueOf((char) unit);
		}
		if (unit <= 0x7f || unitWidth == 1) {
			return String.format("\\%03o", unit);
		}
		if ((unit >= 0xd800 && unit <= 0xdfff) || unit > 0x10ffff) {
			return null;
		}
		if (unit <= 0xffff) {
			return String.format("\\u%04x", unit);
		}
		return String.format("\\U%08x", unit);
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
	 * one. (Duplicated from the placement driver as an honest per-form twin, per the DD-0026
	 * rule-of-three convention, until a third user earns the extraction.)
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
	 * verbatim would emit the misleading {@code new C(UNNAMED)}. Treating {@code "UNNAMED"} (and a
	 * null/blank name) as no-name makes the whole hint decline instead, keeping the never-wrong contract
	 * ({@code #37-10j}); rendering the underlying string literal / compound expression is later
	 * {@code #37-10} work.
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
