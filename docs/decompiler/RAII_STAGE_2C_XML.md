# Rec 31 Stage 2C — `xml.y` / `xml.cc` semantic-action RAII migration

*Status: design / scoping. No code change in this PR; describes the work that Stage 2C entails so a future PR can be scoped accurately.*

## What's left after Stage 2B

PRs [#51](https://github.com/CryptoJones/GayHydra/pull/51) (`XmlScan::lvalue`) and [#52](https://github.com/CryptoJones/GayHydra/pull/52) (`xml_parse::global_scan` lifetime) converted the *epilogue*-section raw `new`s in `xml.y` / `xml.cc` — the hand-written C++ that bison passes through verbatim. The migration was minimal-disruption because the edits were entirely outside the bison-generated table-driven `yyparse` body.

The remaining raw `new` sites live **inside bison semantic actions**:

    xml.y:150  $$ = new string;          // attsinglemid
    xml.y:153  $$ = new string;          // attdoublemid
    xml.y:198  $$ = new Attributes($2);  // stagstart
    xml.y:200  $$ = new NameValue;       // SAttribute
    xml.y:208  string *tmp = new string(); ...
               (Reference; tmp owned locally, deleted before return)
    xml.y:538  Element *newel = new Element(cur);  // TreeHandler::startElement
    xml.y:624  Document *doc = new Document();      // xml_tree()

These get inlined into `yyparse`'s big switch statement when bison compiles the grammar. The corresponding parallel sites in `xml.cc` are:

    xml.cc:1598 (yyval.str)  = new string;
    xml.cc:1616 (yyval.str)  = new string;
    xml.cc:1736 (yyval.attr) = new Attributes((yyvsp[0].str));
    xml.cc:1748 (yyval.pair) = new NameValue; ...
    xml.cc:1790 string *tmp = new string(); ...

## Why this isn't a hand-edit

Stages 2A (marshal) and 2B (xml.y epilogue) succeeded with the *parallel-hand-edit* technique: change `xml.y` and `xml.cc` consistently in the regions bison copies verbatim, no bison regeneration required.

The semantic-action sites can't be hand-edited safely because:

1. `xml.cc:1598` etc. live inside the bison-generated `yyparse()` body — a state machine driven by the `yytable` / `yydefact` / etc. arrays that bison computes from the grammar. Hand-edits there are robust as long as the surrounding code shape doesn't change.

2. **But the semantic actions cross the bison `%union` boundary.** The `%union` declaration in `xml.y` is:

        %union {
          int4 i;
          string *str;
          Attributes *attr;
          NameValue *pair;
        }

   It's a C-style union of raw pointers. The RAII candidate replacements (`unique_ptr<string>`, `unique_ptr<Attributes>`, etc.) **cannot live in a C `union`** — unions require trivially-constructible / trivially-destructible members, which `unique_ptr` is not.

3. The grammar also declares destructors:

        %destructor { delete $$; } <*>

   bison generates calls to this whenever the parser discards a semantic value on its stack (e.g. during error recovery). If we change the value type to a smart pointer, the destructor body changes shape too.

So Stage 2C is **not** "change `new` to `make_unique` in five places and call it done." The grammar's value-type model itself has to change.

## Approach options

### Option A — switch to `%define api.value.type variant` (bison's C++ variants)

Bison 3.0 introduced an opt-in C++ tagged-variant value type:

    %language "c++"
    %require "3.0"
    %define api.value.type variant
    %token <unique_ptr<string>> CHARDATA CDATA ATTVALUE ...
    %type  <unique_ptr<string>> AttValue attsinglemid attdoublemid ...
    %type  <unique_ptr<Attributes>> EmptyElemTag STag stagstart
    %type  <unique_ptr<NameValue>> SAttribute

This *does* support non-trivially-destructible value types. The generated parser:

- becomes a C++ class (`xml::parser` instead of a free `yyparse` function);
- uses move semantics on the value stack;
- has the destructors automatically called when a value is popped — no `%destructor { delete $$; }` needed.

**Pros:** clean RAII end-to-end. Eliminates manual `delete` in actions (`delete $2; *$$ += *$2;` becomes `*$$ += std::move(*$2);` etc.). The bison maintainers' recommended path for new C++ grammars.

**Cons:** large API break in the generated code. `xml_parse()`'s body has to be rewritten: `int res = yyparse();` becomes something like `xml::parser p; int res = p.parse();`. `yylex` signature changes — instead of writing to global `yylval`, the lexer fills a parser-supplied `xml::parser::symbol_type`. `yyerror` becomes a member. The diff against the current `xml.cc` would be wholesale rewrite (~2700 of the ~2700 lines).

The wholesale rewrite is the right move long-term but requires:
- Pin the bison version (3.0.5 is locally buildable; 3.0.4 isn't on modern glibc). Document it in the Makefile + CONTRIBUTING.
- Write a from-scratch xml_parse shim that bridges the new C++ parser API to the existing `xml_parse(istream &, ContentHandler *, int4)` interface that decompiler callers depend on.
- Verify the bison-regenerated `xml.cc` produces the same parse trees on the existing XML corpus.

### Option B — keep `%union` of raw pointers; manage ownership tighter at the boundary

Leave the `%union` as-is (raw pointers everywhere on the parser value stack), but:

- Audit every semantic action that calls `new X(...)` and ensure the corresponding "ownership transfer in" / "consumer deletes" / "drop on error" rules are explicit.
- Convert the `string *tmp = new string(); ... delete tmp;` pattern in line 208 to a stack-local `string tmp;` (no `new` needed, no `delete` needed — it's used once within the action then goes out of scope).
- Accept that the `%destructor { delete $$; }` is the project's RAII story for the parser value stack.
- Add `xml.y` / `xml.cc` to `cppRaiiAudit`'s `PROTECTED_FILES` once the obvious sites (208's `tmp`) are cleaned, with the understanding that the `%union` raw-pointer pattern is grandfathered as bison's contract, not user-level raw ownership.

**Pros:** small, mechanical, no bison-version dance, no API break. Lines 208 (the `tmp`) is the only one that's an unambiguous code-smell; the rest are bison value-stack convention.

**Cons:** doesn't actually achieve "no raw `new` in `xml.cc`" — it just decides that some raw-`new`s are OK because bison wrote them. The audit gate has to carry an explicit exception for the `%union`-mediated sites. Slightly weakens the Rec 31 invariant.

## Recommendation

**Option B for the next PR; Option A as a separate strategic project.**

The `string *tmp = new string(); ... delete tmp;` at line 208 is a clear win — it's a stack-local that the author wrote as heap-allocated for no apparent reason. Convert it. The other five semantic-action `new`s are bison's value-stack contract; leaving them as raw pointers and adding `xml.y` / `xml.cc` to `cppRaiiAudit`'s `PROTECTED_FILES` with a documented exception is honest and shipping-ready.

Option A is the right *eventual* destination but its scope is "rewrite the xml parser front-end." That needs its own sprint, its own design review with the decompiler maintainer, its own corpus-equivalence verification. Not something to slip into a multi-PR thread.

## Concrete next actions (in order)

1. **PR — Stage 2C-min (Option B):** convert `xml.y:208`'s `string *tmp = new string(); ... delete tmp;` to a stack-local; mirror in `xml.cc:1790`. **shipped** in [PR #77](https://github.com/CryptoJones/GayHydra/pull/77). ~~Add `xml.y` / `xml.cc` to `cppRaiiAudit`'s `PROTECTED_FILES`~~ **shipped**: `cppRaiiAudit.gradle`'s `PROTECTED_FILES` became a `Map<String, List<List<Integer>>>` (file → list of `[startLine, endLine]` excluded ranges); `xml.y` lines `150`, `153`, `198`, `200` and `xml.cc` lines `1598`, `1616`, `1736`, `1748` are excluded as the bison `%union` semantic-action sites. The audit fires on every other line of both files; any new raw `new` outside those four sites trips CI.

2. **PR — Element parse-tree ownership:** ~~the `xml.y:538` `new Element(cur)` is parse-tree owned by the parent's `children` vector. Convert `Element::children` to `vector<unique_ptr<Element>>` and update the few callers. This is independent of the `%union` question.~~ **shipped:** `Element::children` is now `vector<unique_ptr<Element>>`; `~Element` is `= default`; `addChild` takes `unique_ptr<Element>`; the call site at `xml.y:538` (TreeHandler::startElement) builds a `make_unique<Element>(cur)`, captures a raw observer (`Element *newel = owned.get()`) for `cur` to follow, then transfers ownership via `cur->addChild(move(owned))`. 6 consumer files updated to use `iter->get()` instead of `*iter` when extracting raw `Element *` from `List::const_iterator`.

3. **PR — Document return:** ~~`xml.y:624` `new Document()` flows out through `xml_tree()` to `XmlDecode::ingestStream` where `XmlDecode::~XmlDecode` does `delete document;`. Convert `xml_tree` to return `unique_ptr<Document>` and `XmlDecode::document` to `unique_ptr<Document>`. Drop the manual destructor.~~ **shipped:** scope was slightly wider than the original sketch — four owners hold a `Document *` (`XmlDecode::document`, `DocumentStorage::doclist`, `InjectPayloadDynamic::addrMap`, plus a local in `slgh_compile.cc::ProcessorCompile`); all four migrated. Each owner's manual destructor collapses to `= default`; the manual "delete preexisting before reassignment" in `InjectPayloadDynamic::decodeEntry` becomes implicit via map `operator[] = move()`. After this, the xml.y / xml.cc epilogue is raw-`new`-free.

4. **Strategic sprint — Option A:** switch `xml.y` to `%define api.value.type variant`, regenerate `xml.cc` with bison-3.0.5, write the C++-parser shim. Coordinate with decompiler maintainer.

## Bison version note

Local builds of bison-3.0.4 fail on modern glibc due to a gnulib `fseterr.c` portability bug. **Bison-3.0.5** builds cleanly (verified in this session at `/tmp/bison30/bin/bison`) and the diff against `xml.cc` (which was generated by 3.0.4) is ~615 lines, almost all cosmetic (`#line` directives, version-string bumps, a `default:` case added in 3.0.5). If a regeneration ever lands, pin **bison-3.0.5** in `decompile/cpp/Makefile` and a `CONTRIBUTING` note.

---

*Proudly Made in Nebraska. Go Big Red! 🌽 <https://xkcd.com/2347/>*
