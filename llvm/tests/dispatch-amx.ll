target triple = "x86_64-unknown-linux-gnu"

declare x86_amx @llvm.x86.cast.vector.to.tile.v16i32(<16 x i32>)
declare <16 x i32> @llvm.x86.cast.tile.to.vector.v16i32(x86_amx)

define <16 x i32> @method_tile(i1 %condition, <16 x i32> %input) {
entry:
  %tile = call x86_amx @llvm.x86.cast.vector.to.tile.v16i32(<16 x i32> %input)
  br i1 %condition, label %yes, label %no
yes:
  %value = call <16 x i32> @llvm.x86.cast.tile.to.vector.v16i32(x86_amx %tile)
  ret <16 x i32> %value
no:
  ret <16 x i32> zeroinitializer
}
