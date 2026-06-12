// Hint-recall corpus source (Rec 37; meta-review 2026-06-11 measurement band).
//
// One exported call site per hint form, for per-form recall attribution.
// Deliberately compiled with DWARF (-g) and with NON-TRIVIAL OUT-OF-LINE
// constructors/destructors, because the 2026-06-12 investigation proved a
// trivial-ctor / non-DWARF corpus cannot exercise the type-resolving forms:
//   - DWARF gives the decompiler typed receivers (a `Base *` param, not
//     `undefined8`), which the recognition drivers resolve against the fed
//     hierarchy (the gap the canAnalyze gate fix opened).
//   - Out-of-line ctors/dtors emit real, locally-resolvable calls instead of
//     being inlined away, so construction/destructor idioms actually appear.
//
// Self-declared allocator functions keep the cross-compiles header-free (no
// sysroot for the aarch64 legs); polymorphic classes still emit vtables +
// Itanium RTTI, which the Cpp* analyzers feed from. Deliberately unstripped:
// callee-name classification is part of the measured contract.
//
// The committed objects/<cc>-<arch>-<opt>.o bytes ARE the corpus — recall is
// pinned to exact codegen; build.sh regeneration is a deliberate
// baseline-update event, never CI.

typedef unsigned long size_t_;

void *operator new(size_t_ n);
void *operator new[](size_t_ n);
void *operator new(size_t_ n, void *p);
void operator delete(void *p);
void operator delete[](void *p);

struct Base {
  long pad;
  Base();                       // out-of-line default + value ctors
  Base(long v);
  virtual ~Base();
  virtual long draw();
};
struct Derived : Base {
  long extra;
  Derived();
  Derived(long v);
  virtual long draw();
};
struct Other {
  long first;
  virtual long id();
};
struct Derived2 : Other, Base { // Base is the second base -> non-zero offset
  long tail;
  Derived2(long v);
};

Base::Base() { pad = 1; }
Base::Base(long v) { pad = v; }
Base::~Base() { pad = 0; }
long Base::draw() { return pad; }
Derived::Derived() : Base(), extra(0) {}
Derived::Derived(long v) : Base(v) { extra = v; }
long Derived::draw() { return extra; }
long Other::id() { return first; }
Derived2::Derived2(long v) : Other(), Base(v) { tail = v; }

// #37-9b: heap construction (operator new + non-trivial ctor).
Base *form_heap_new(long v)       { return new Base(v); }
// #37-9d: array construction.
Derived *form_array_new()         { return new Derived[5]; }
// #37-9e: placement construction (non-elided: buffer-carrying operator new).
Base *form_placement_new(void *b) { return new (b) Base(7); }
// #37-9f: delete.
void  form_delete(Base *b)        { delete b; }
// #37-9c: explicit destructor call.
void  form_destructor(Base *b)    { b->~Base(); }
// #37-7b: virtual call.
long  form_virtual_call(Base *b)  { return b->draw(); }
// #37-8b: base up/down-cast (Base is Derived2's second base -> non-zero offset).
Base *form_upcast(Derived2 *d)    { return static_cast<Base *>(d); }
Derived2 *form_downcast(Base *b)  { return static_cast<Derived2 *>(b); }
