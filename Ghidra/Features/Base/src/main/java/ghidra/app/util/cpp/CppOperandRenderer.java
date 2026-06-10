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
import java.util.List;

import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressOutOfBoundsException;
import ghidra.program.model.data.AbstractFloatDataType;
import ghidra.program.model.data.AbstractIntegerDataType;
import ghidra.program.model.data.BooleanDataType;
import ghidra.program.model.data.CharDataType;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.Enum;
import ghidra.program.model.data.Pointer;
import ghidra.program.model.data.Undefined;
import ghidra.program.model.data.WideChar16DataType;
import ghidra.program.model.data.WideChar32DataType;
import ghidra.program.model.data.WideCharDataType;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryAccessException;
import ghidra.program.model.pcode.HighVariable;
import ghidra.program.model.pcode.PcodeOp;
import ghidra.program.model.pcode.Varnode;

/**
 * The shared call-operand/argument expression renderer of the Rec 37 {@code #37-10} band: turns the
 * varnodes of a recognized call's explicit arguments (and their nested operands) into faithful C++
 * source expressions &mdash; names, the {@code #37-10c}&ndash;{@code l} typed literals
 * (integer/bool/char/wide-char/float/enum/string), and the {@code #37-10m}&ndash;{@code s}
 * compound-expression grammar (binary, unary, comparison, with unconditional parentheses on nested
 * compounds).
 *
 * <p>Extracted verbatim from the heap and placement construction drivers' per-form twins when the
 * virtual-call driver became the third user (rule of three, DD-0026's convention; the twins'
 * grounding history lives in DD-0042..DD-0060). <b>Never-wrong is the contract:</b> every method
 * returns {@code null} for anything it cannot render faithfully, leaving the caller to decline the
 * whole hint rather than render a gap or a guess.
 */
final class CppOperandRenderer {

	/** A recognized call's input 0 is the call target; input 1 is the {@code this}/receiver. */
	static final int RECEIVER_INPUT_INDEX = 1;

	/** A string-pointer argument's def-chain is traced through at most this many COPY/CAST pass-throughs. */
	private static final int MAX_STRING_DEF_HOPS = 4;

	/**
	 * Defense-in-depth bound on {@link #operandExpr}'s recursion into nested compound operands
	 * ({@code #37-10r}). Recursion already terminates structurally (only mapped binary/unary opcodes
	 * recurse; the SSA cycle-closers {@code MULTIEQUAL}/{@code INDIRECT} are unmapped), so the bound
	 * exists to cap rendering depth, not to fix a known divergence; eight levels is far past any
	 * readable call argument.
	 */
	private static final int MAX_OPERAND_NESTING = 8;

	/** A traced string is read up to this many bytes before declining a missing NUL terminator. */
	private static final int MAX_STRING_LENGTH = 4096;

	private CppOperandRenderer() {
		// static renderer utility
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
	static List<String> callArguments(PcodeOp call, Program program) {
		List<String> arguments = new ArrayList<>();
		for (int i = RECEIVER_INPUT_INDEX + 1; i < call.getNumInputs(); i++) {
			String argument = argumentExpr(call.getInput(i), program);
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
	 * {@code param_1 + 7} ({@code #37-10m}) or a single-operand <em>unary expression</em>
	 * ({@link #unaryExpr}) such as {@code -param_1} ({@code #37-10o}), or a zero-extended
	 * <em>comparison</em> ({@link #comparisonExpr}) such as {@code param_1 == 7} or {@code param_1 < 7}
	 * ({@code #37-10p}, {@code #37-10q}). An argument that is none of these declines the whole hint
	 * ({@code null}), keeping the never-wrong contract.
	 *
	 * @return the rendered argument expression, or null if it is neither a renderable leaf, binary
	 *         expression, unary expression, nor comparison
	 */
	static String argumentExpr(Varnode varnode, Program program) {
		String leaf = leafExpr(varnode, program);
		if (leaf != null) {
			return leaf;
		}
		String binary = binaryExpr(varnode, program);
		if (binary != null) {
			return binary;
		}
		String unary = unaryExpr(varnode, program);
		if (unary != null) {
			return unary;
		}
		return comparisonExpr(varnode, program);
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
	 * expression.
	 *
	 * @return the rendered leaf expression, or null if it is neither a named variable, a narrow or wide
	 *         string pointer, nor a boolean/char/enum/integer-typed constant
	 */
	static String leafExpr(Varnode varnode, Program program) {
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
			if (type instanceof Undefined) {
				return undefinedConstantLiteral(varnode);
			}
		}
		return null;
	}

	/**
	 * {@return the decimal literal for a prototype-less constant whose raw bits read the same under
	 * signed and unsigned interpretation at the varnode width &mdash; the width's sign bit is clear
	 * &mdash; or null otherwise}
	 *
	 * <p>An argument recovered from a call with no prototype (an unresolved indirect {@code CALLIND},
	 * {@code #37-10t}) arrives typed {@code undefinedN}: the bits are known but the signedness is not.
	 * A constant whose sign bit is clear renders to the same decimal under either reading, so its
	 * value is faithful; a sign-bit-set pattern ({@code -1} vs {@code 18446744073709551615}) is
	 * ambiguous and declines (never-wrong).
	 */
	private static String undefinedConstantLiteral(Varnode varnode) {
		long value = varnode.getOffset();
		int bits = varnode.getSize() * 8;
		if (bits <= 0 || bits > 64) {
			return null;
		}
		if (bits < 64) {
			value &= (1L << bits) - 1;
			if ((value & (1L << (bits - 1))) != 0) {
				return null;
			}
		}
		else if (value < 0) {
			return null;
		}
		return Long.toString(value);
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
	 * C++ glyph for the opcode ({@link #binaryOperator}) and each operand is rendered by
	 * {@link #operandExpr}: a <em>leaf</em> ({@link #leafExpr}) renders bare, and a <em>nested
	 * compound</em> &mdash; an operand that is itself a recognised binary or unary op &mdash; renders
	 * recursively, always wrapped in parentheses ({@code #37-10m}, {@code #37-10r}).
	 *
	 * <p>Only a two-input op is considered, and only one whose opcode {@link #binaryOperator} maps to a
	 * glyph; any other definition declines. A deliberately declined case is a <em>logical</em> right shift
	 * ({@code INT_RIGHT}) whose left operand the decompiler wrapped in a {@code CAST} to an unsigned type:
	 * peeling that cast and rendering a bare {@code param_1 >> 3} over a signed operand would silently
	 * become an <em>arithmetic</em> shift. A {@code CAST} is neither a leaf nor a mapped binary/unary
	 * opcode, so {@link #operandExpr} declines it, and the hint declines &mdash; faithful over complete. An
	 * <em>arithmetic</em> right shift ({@code INT_SRIGHT}) carries its operand directly and renders.
	 *
	 */
	static String binaryExpr(Varnode varnode, Program program) {
		return binaryExpr(varnode, program, 0);
	}

	static String binaryExpr(Varnode varnode, Program program, int depth) {
		PcodeOp def = varnode.getDef();
		if (def == null || def.getNumInputs() != 2) {
			return null;
		}
		String operator = binaryOperator(def.getOpcode());
		if (operator == null) {
			return null;
		}
		String left = operandExpr(def.getInput(0), program, depth);
		if (left == null) {
			return null;
		}
		String right = operandExpr(def.getInput(1), program, depth);
		if (right == null) {
			return null;
		}
		return left + " " + operator + " " + right;
	}

	/**
	 * {@return one operand of a compound constructor-argument expression, rendered either as a bare
	 * <em>leaf</em> or as a parenthesised <em>nested compound</em>, or null when it is neither}
	 *
	 * <p>A leaf ({@link #leafExpr}) renders bare, exactly as the one-level {@code #37-10m} forms always
	 * have. An operand that is not a leaf but is itself a recognised binary or unary op renders
	 * recursively and is <em>always wrapped in parentheses</em> ({@code #37-10r}):
	 * {@code new C((param_1 & 7) + 1)}, {@code new C(-(param_1 & 7))}. Unconditional parentheses on every
	 * nested sub-expression make the rendering exact by construction &mdash; no C precedence or
	 * associativity table is consulted, so there is no table to get wrong &mdash; at the cost of an
	 * occasional redundant pair (e.g. {@code (~param_1) & 7}, where {@code ~} already binds tighter).
	 * Faithful over pretty, the band's standing trade.
	 *
	 * <p>Recursion is bounded by {@link #MAX_OPERAND_NESTING} as defense-in-depth. Structurally it
	 * already terminates: only opcodes mapped in {@link #binaryOperator}/{@link #unaryOperator} recurse,
	 * and the ops that could close an SSA def-chain cycle through a loop ({@code MULTIEQUAL},
	 * {@code INDIRECT}) are unmapped and decline. An operand that is neither a leaf nor a recognised
	 * nested compound (a {@code CAST}-wrapped temporary, an unrecognised opcode, anything past the bound)
	 * returns null and the whole hint declines &mdash; the never-wrong contract is unchanged.
	 *
	 */
	static String operandExpr(Varnode varnode, Program program, int depth) {
		String leaf = leafExpr(varnode, program);
		if (leaf != null) {
			return leaf;
		}
		if (depth >= MAX_OPERAND_NESTING) {
			return null;
		}
		String binary = binaryExpr(varnode, program, depth + 1);
		if (binary != null) {
			return "(" + binary + ")";
		}
		String unary = unaryExpr(varnode, program, depth + 1);
		if (unary != null) {
			return "(" + unary + ")";
		}
		return null;
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
	 *
	 */
	static String binaryOperator(int opcode) {
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
	 * {@return the constructor-argument varnode rendered as a one-level C++ <em>unary expression</em>
	 * such as {@code -param_1} or {@code ~param_1}, or null when its definition is not a recognised
	 * single-operand arithmetic-negation or bitwise-complement p-code op over a leaf operand}
	 *
	 * <p>The argument arrives as an unnamed temporary whose {@link Varnode#getDef() definition} is the
	 * unary p-code op that computed it (grounded: {@code new C(-v)} arrives as an {@code UNNAMED}
	 * {@code HighOther} defined by {@code INT_2COMP} of the named {@code param_1}; {@code new C(~v)} by
	 * {@code INT_NEGATE}). This helper renders that op as {@code OP operand} where {@code OP} is the C++
	 * glyph for the opcode ({@link #unaryOperator}) and the operand is rendered by {@link #operandExpr}:
	 * a <em>leaf</em> ({@link #leafExpr}) renders bare ({@code -param_1}), and a nested compound renders
	 * recursively in parentheses ({@code -(param_1 & 7)}, {@code #37-10r}); a unary prefix binds tighter
	 * than any binary operator, so the parenthesised-operand result is never precedence-ambiguous. An
	 * operand that is neither makes {@code operandExpr} decline, so the whole hint declines &mdash;
	 * faithful over complete, the same contract {@link #binaryExpr} keeps for the two-operand forms
	 * ({@code #37-10o}).
	 *
	 * <p>Both mapped opcodes preserve the operand's width (an {@code n}-byte {@code INT_2COMP} /
	 * {@code INT_NEGATE} produces an {@code n}-byte result), so &mdash; unlike a comparison, whose 1-byte
	 * boolean result the decompiler widens to the argument slot with an intervening {@code INT_ZEXT}
	 * &mdash; the unary op is the value varnode's direct definition and no cast/extension peeling is
	 * needed.
	 */
	static String unaryExpr(Varnode varnode, Program program) {
		return unaryExpr(varnode, program, 0);
	}

	static String unaryExpr(Varnode varnode, Program program, int depth) {
		PcodeOp def = varnode.getDef();
		if (def == null || def.getNumInputs() != 1) {
			return null;
		}
		String operator = unaryOperator(def.getOpcode());
		if (operator == null) {
			return null;
		}
		String operand = operandExpr(def.getInput(0), program, depth);
		if (operand == null) {
			return null;
		}
		return operator + operand;
	}

	/**
	 * {@return the C++ operator glyph for a single-operand integer p-code opcode ({@code -} for
	 * {@code INT_2COMP} arithmetic negation, {@code ~} for {@code INT_NEGATE} bitwise complement), or
	 * null when the opcode is neither}
	 *
	 * <p>The mapping is grounded over the opcodes the decompiler emits for the corresponding C operations
	 * ({@code #37-10o}): an arithmetic unary minus is {@code INT_2COMP} and a bitwise {@code ~} is
	 * {@code INT_NEGATE}. The logical {@code !} ({@code BOOL_NEGATE}) is deliberately not mapped: like a
	 * comparison its 1-byte result is widened to the argument slot by an intervening {@code INT_ZEXT}, so
	 * it is not the value varnode's direct definition and would need extension peeling this band does not
	 * yet do.
	 */
	static String unaryOperator(int opcode) {
		return switch (opcode) {
			case PcodeOp.INT_2COMP -> "-";
			case PcodeOp.INT_NEGATE -> "~";
			default -> null;
		};
	}

	/**
	 * {@return the constructor-argument varnode rendered as a C++ <em>comparison</em> such as
	 * {@code param_1 == 7} or {@code (param_1 & 7) < 5}, or null when its definition is not a
	 * zero-extended two-operand equality-or-relational p-code op over renderable operands}
	 *
	 * <p>A comparison produces a one-byte boolean, but a constructor argument slot is wider (e.g. an
	 * eight-byte {@code longlong}), so the decompiler widens the boolean to the slot with an
	 * {@code INT_ZEXT}: the value varnode's direct definition is the {@code INT_ZEXT}, and the comparison
	 * op sits one hop below it (grounded: {@code new C(v == 7)} arrives as an {@code INT_ZEXT} of an
	 * {@code INT_EQUAL} of the named {@code param_1} and the constant {@code 7}). This helper peels
	 * <em>exactly one</em> {@code INT_ZEXT} to reach the comparison, then renders it as
	 * {@code operandExpr(in0) OP operandExpr(in1)} over a grounded comparison-opcode→glyph map
	 * ({@link #comparisonOperator}). Each operand is rendered by {@link #operandExpr}: a leaf renders
	 * bare ({@code #37-10p}, {@code #37-10q}), and a nested compound renders recursively in parentheses
	 * ({@code new C((param_1 & 7) == 5)}, {@code #37-10s}); a cast-wrapped or unrecognised operand
	 * declines the whole hint, the same faithful no-peel rule {@link #binaryExpr} keeps. (A unary
	 * compound under a comparison is typically never seen: the decompiler folds it into the constant
	 * &mdash; grounded, {@code ~v == 5} arrives as {@code INT_EQUAL(param_1, -6)} and renders at leaf
	 * level.)
	 *
	 * <p>The symmetric equality operators ({@code ==}, {@code !=}) carry no signed/unsigned distinction
	 * and no operand order to recover, so they render unambiguously ({@code #37-10p}). The relational
	 * operators render the decompiler's <em>canonical</em> strict-less-than form faithfully ({@code
	 * #37-10q}): the decompiler normalises every signed relational source form to {@code INT_SLESS} by
	 * adjusting the constant or swapping the operands, so {@code v < 7} renders {@code param_1 < 7},
	 * {@code v <= 7} renders {@code param_1 < 8}, {@code v > 7} renders {@code 7 < param_1}, and
	 * {@code v >= 7} renders {@code 6 < param_1} &mdash; each the exact boolean the p-code computes, which
	 * is all this band ever claims to render. An <em>unsigned</em> relational form casts its operand to an
	 * unsigned type (as unsigned division does); a {@code CAST} is neither a leaf nor a mapped opcode, so
	 * {@code operandExpr} declines it and the whole hint declines rather than silently change signedness,
	 * exactly the signed/unsigned split {@link #binaryOperator} keeps. The extension is matched as
	 * {@code INT_ZEXT} specifically &mdash; a one-byte boolean is zero-, never sign-, extended &mdash; and
	 * exactly one hop is peeled, so an arbitrary cast chain is not silently flattened.
	 */
	static String comparisonExpr(Varnode varnode, Program program) {
		PcodeOp widen = varnode.getDef();
		if (widen == null || widen.getOpcode() != PcodeOp.INT_ZEXT || widen.getNumInputs() != 1) {
			return null;
		}
		PcodeOp def = widen.getInput(0).getDef();
		if (def == null || def.getNumInputs() != 2) {
			return null;
		}
		String operator = comparisonOperator(def.getOpcode());
		if (operator == null) {
			return null;
		}
		String left = operandExpr(def.getInput(0), program, 0);
		if (left == null) {
			return null;
		}
		String right = operandExpr(def.getInput(1), program, 0);
		if (right == null) {
			return null;
		}
		return left + " " + operator + " " + right;
	}

	/**
	 * {@return the C++ operator glyph for a two-operand comparison p-code opcode ({@code ==} for
	 * {@code INT_EQUAL}, {@code !=} for {@code INT_NOTEQUAL}, {@code <} for the signed {@code INT_SLESS}
	 * and unsigned {@code INT_LESS}), or null when the opcode is none of these}
	 *
	 * <p>The mapping is grounded over the opcodes the decompiler emits for the corresponding C operations
	 * ({@code #37-10p}, {@code #37-10q}). The equality operators are symmetric and carry no signedness, so
	 * they render directly. For the relational operators the decompiler canonicalises <em>every</em> source
	 * form &mdash; {@code <}, {@code <=}, {@code >}, {@code >=} &mdash; to a strict less-than by adjusting
	 * the constant or swapping the operands, so only {@code INT_SLESS}/{@code INT_LESS} (mapped to
	 * {@code <}) ever appear; {@code INT_SLESSEQUAL}/{@code INT_LESSEQUAL} are not emitted for these forms
	 * and so are intentionally unmapped. The signed {@code INT_SLESS} carries its operands directly and
	 * renders; the unsigned {@code INT_LESS} casts its operand to an unsigned type, so
	 * {@link #comparisonExpr}'s leaf-only operand rule declines it (a faithful render would silently change
	 * signedness) &mdash; the identical signed/unsigned split {@link #binaryOperator} keeps for division
	 * and the shifts.
	 */
	static String comparisonOperator(int opcode) {
		return switch (opcode) {
			case PcodeOp.INT_EQUAL -> "==";
			case PcodeOp.INT_NOTEQUAL -> "!=";
			case PcodeOp.INT_SLESS, PcodeOp.INT_LESS -> "<";
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
	 * for negative and wide-unsigned arguments alike ({@code #37-10d}).
	 */
	static String integerConstantLiteral(Varnode varnode, AbstractIntegerDataType type) {
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
	 *
	 */
	static String charConstantLiteral(Varnode varnode) {
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
	 * for {@code wchar_t}/{@code char16_t}/{@code char32_t} respectively.
	 */
	static String wideCharConstantLiteral(Varnode varnode, String prefix) {
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
	 * this slice ({@code #37-10i}).
	 */
	static String floatConstantLiteral(Varnode varnode) {
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
	 * ({@code #37-10k}, {@code #37-10l}).
	 */
	static String stringConstantLiteral(Varnode varnode, Program program) {
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
	static String stringLiteralPrefix(DataType pointee) {
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
	static String readStringLiteral(Program program, long addressOffset, String prefix,
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
	static String escapeStringUnit(long unit, int unitWidth) {
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
	 * one.
	 */
	static String enumConstantLiteral(Varnode varnode, Enum enumType) {
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
	static String operandName(Varnode varnode) {
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
}
