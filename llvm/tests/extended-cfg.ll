define i32 @method_chain(i32 noundef %left, i32 noundef %right) {
  %first = add i32 %left, %right
  %second = add i32 %first, 1
  %third = mul i32 %second, %right
  ret i32 %third
}

define i32 @method_existing_phi(i32 noundef %left, i32 noundef %right) {
entry:
  %odd = trunc i32 %left to i1
  br i1 %odd, label %plus, label %minus

plus:
  %sum = add i32 %left, %right
  br label %join

minus:
  %difference = sub i32 %left, %right
  br label %join

join:
  %value = phi i32 [ %sum, %plus ], [ %difference, %minus ]
  ret i32 %value
}
