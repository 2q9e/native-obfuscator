define i32 @method_sum32(i32 %left, i32 %right) {
  %value = add i32 %left, %right
  ret i32 %value
}

define i64 @method_sub64(i64 %left, i64 %right) {
  %value = sub i64 %left, %right
  ret i64 %value
}

define i32 @method_sum8(i32 %left, i32 %right) {
  %a = trunc i32 %left to i8
  %b = trunc i32 %right to i8
  %value = add i8 %a, %b
  %output = zext i8 %value to i32
  ret i32 %output
}

define i32 @method_sub16(i32 %left, i32 %right) {
  %a = trunc i32 %left to i16
  %b = trunc i32 %right to i16
  %value = sub i16 %a, %b
  %output = zext i16 %value to i32
  ret i32 %output
}

define i32 @method_chain32(i32 %left, i32 %right) {
  %sum = add i32 %left, %right
  %masked = xor i32 %sum, %right
  %difference = sub i32 %masked, %left
  ret i32 %difference
}

define i32 @method_flags32(i32 %left, i32 %right) {
  %value = add nuw nsw i32 %left, %right
  ret i32 %value
}

define i32 @unselected_sum32(i32 %left, i32 %right) {
  %value = add i32 %left, %right
  ret i32 %value
}

define i32 @method_optnone32(i32 %left, i32 %right) noinline optnone {
  %value = add i32 %left, %right
  ret i32 %value
}
