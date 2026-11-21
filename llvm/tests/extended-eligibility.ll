@address = global ptr blockaddress(@method_address_taken, %calculation)

declare i32 @personality(...)
declare void @converges() convergent
declare i32 @twice() returns_twice

define i32 @method_flags(i32 noundef %left, i32 noundef %right) {
  %value = add nuw nsw i32 %left, %right
  ret i32 %value
}

define i32 @method_disjoint(i32 noundef %left, i32 noundef %right) {
  %value = or disjoint i32 %left, %right
  ret i32 %value
}

define i32 @method_exact(i32 noundef %left, i32 noundef %right) {
  %value = udiv exact i32 %left, %right
  ret i32 %value
}

define i24 @method_width(i24 noundef %left, i24 noundef %right) {
  %value = add i24 %left, %right
  ret i24 %value
}

define <2 x i32> @method_vector(<2 x i32> noundef %left, <2 x i32> noundef %right) {
  %value = add <2 x i32> %left, %right
  ret <2 x i32> %value
}

define i32 @method_unknown(i32 %left, i32 %right) {
  %value = add i32 %left, %right
  ret i32 %value
}

define i32 @method_undef(i32 noundef %left) {
  %value = add i32 %left, undef
  ret i32 %value
}

define i32 @method_poison(i32 noundef %left) {
  %value = sub i32 %left, poison
  ret i32 %value
}

define i32 @method_frozen(i32 %left, i32 %right) {
  %a = freeze i32 %left
  %b = freeze i32 %right
  %value = add i32 %a, %b
  ret i32 %value
}

define i32 @method_constant() {
  %value = sub i32 17, 2
  ret i32 %value
}

define i32 @method_identity(i32 %input) {
  ret i32 %input
}

define i32 @method_optnone(i32 noundef %left, i32 noundef %right) noinline optnone {
  %value = add i32 %left, %right
  ret i32 %value
}

define i32 @unselected(i32 noundef %left, i32 noundef %right) {
  %value = add i32 %left, %right
  ret i32 %value
}

define i32 @method_address_taken(i32 noundef %left, i32 noundef %right) {
  br label %calculation

calculation:
  %value = add i32 %left, %right
  ret i32 %value
}

define i32 @method_indirect(i32 noundef %left, i32 noundef %right) {
  indirectbr ptr blockaddress(@method_indirect, %taken), [label %taken]

taken:
  %value = add i32 %left, %right
  ret i32 %value
}

define i32 @method_convergent(i32 noundef %left, i32 noundef %right) convergent {
  %value = add i32 %left, %right
  ret i32 %value
}

define i32 @method_convergent_call(i32 noundef %left, i32 noundef %right) {
  call void @converges()
  %value = add i32 %left, %right
  ret i32 %value
}

define i32 @method_returns_twice_call(i32 noundef %left, i32 noundef %right) {
  %ignored = call i32 @twice()
  %value = add i32 %left, %right
  ret i32 %value
}

define i32 @method_personality(i32 noundef %left, i32 noundef %right) personality ptr @personality {
  %value = add i32 %left, %right
  ret i32 %value
}

define i32 @method_naked() naked {
  %value = add i32 11, 42
  ret i32 %value
}

define i32 @method_gc(i32 noundef %left, i32 noundef %right) gc "statepoint-example" {
  %value = add i32 %left, %right
  ret i32 %value
}
