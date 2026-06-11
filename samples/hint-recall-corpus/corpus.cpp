// Hint-recall corpus source (meta-review 2026-06-11, Tier 3 item 9;
// SprintPlanning.md "surfacing & measurement" band).
//
// One call site per Rec 37 hint form, each in its own exported function so
// recall is attributable per form per binary. The committed .o artifacts in
// build/ are the *fixed* corpus — the metric must not drift with toolchain
// versions, so the baseline is pinned to those exact bytes; build.sh exists
// to regenerate them deliberately (a baseline-update event, not CI).
//
// Self-declared allocator functions keep the cross-compiles header-free
// (no sysroot needed for the aarch64 legs); polymorphic classes still emit
// vtables + Itanium RTTI, which the Cpp* analyzers feed from. Deliberately
// unstripped: the recognition drivers classify callees by demangled name,
// so symbol presence is part of the measured contract (stripped-binary
// recall is a known zero today — a future corpus column, not this one).

typedef unsigned long size_t_;

void *operator new(size_t_ n);
void *operator new[](size_t_ n);
void *operator new(size_t_ n, void *p);
void operator delete(void *p);
void operator delete(void *p, size_t_ n);
void operator delete[](void *p);

struct Base {
  long pad;
  virtual ~Base();
  virtual long draw();
};

struct Derived : Base {
  long extra;
  virtual long draw();
};

Base::~Base() {}
long Base::draw() { return pad; }
long Derived::draw() { return extra; }

// #37-7b: virtual call through a base pointer.
long form_virtual_call(Base *b) {
  return b->draw();
}

// #37-9b: heap construction (operator new + ctor fusion).
Base *form_heap_new(long v) {
  Base *b = new Base();
  b->pad = v;
  return b;
}

// #37-9d: array construction (operator new[], trivial-element shape).
Derived *form_array_new() {
  return new Derived[5];
}

// #37-9e: placement construction (non-elided two-call shape; the matcher
// requires a real placement operator new carrying the buffer operand).
Base *form_placement_new(void *buf) {
  return new (buf) Base();
}

// #37-9f: delete through a pointer.
void form_delete(Base *b) {
  delete b;
}

// #37-9c: explicit destructor call.
void form_destructor(Base *b) {
  b->~Base();
}

// #37-8: base cast — upcast (in-layout PTRSUB shape needs a non-zero
// offset, so Derived2 puts Base second via multiple inheritance).
struct Other {
  long first;
  virtual long id();
};
long Other::id() { return first; }

struct Derived2 : Other, Base {
  long tail;
};

Base *form_upcast(Derived2 *d) {
  return static_cast<Base *>(d);
}

Derived2 *form_downcast(Base *b) {
  return static_cast<Derived2 *>(b);
}
