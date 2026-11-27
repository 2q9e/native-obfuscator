target triple = "x86_64-w64-windows-gnu"

declare void @llvm.localescape(...)
declare void @llvm.gcroot(ptr, ptr)

define i32 @method_frame_escape(i32 noundef %left, i32 noundef %right) {
entry:
  %slot = alloca i32, align 4
  %before_escape = add i32 %left, %right
  call void (...) @llvm.localescape(ptr %slot) memory(none)
  %after_escape = sub i32 %left, %right
  %condition = icmp sgt i32 %before_escape, %after_escape
  br i1 %condition, label %first, label %second
first:
  ret i32 %before_escape
second:
  ret i32 %after_escape
}

define i32 @method_late_alloca(i32 noundef %left, i32 noundef %right) {
entry:
  %before_alloca = add i32 %left, %right
  %slot = alloca i32, align 4
  store volatile i32 %before_alloca, ptr %slot, align 4
  %after_alloca = sub i32 %left, %right
  %saved = load volatile i32, ptr %slot, align 4
  %result = xor i32 %saved, %after_alloca
  ret i32 %result
}

define i32 @method_gcroot(i32 noundef %left, i32 noundef %right) gc "shadow-stack" {
entry:
  %root = alloca ptr, align 8
  %before_root = add i32 %left, %right
  call void @llvm.gcroot(ptr %root, ptr null)
  %after_root = sub i32 %before_root, %right
  ret i32 %after_root
}
