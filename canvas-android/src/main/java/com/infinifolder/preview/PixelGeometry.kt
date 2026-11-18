package com.infinifolder.preview

/** Representation checks for the Android RGBA bitmap backend; no editor resolution policy. */
object PixelGeometry {
    @JvmStatic fun validate(width:Int,height:Int) {
        require(width>0 && height>0) { "Pixel dimensions must be positive" }
        require(width.toLong()*height<=Int.MAX_VALUE/4) { "Image exceeds Android's addressable RGBA bitmap size" }
    }
    @JvmStatic fun validateCrop(x:Int,y:Int,width:Int,height:Int) {
        validate(width,height)
        require(x>=0 && y>=0 && x.toLong()+width<=Int.MAX_VALUE && y.toLong()+height<=Int.MAX_VALUE) { "Invalid pixel crop coordinates" }
    }
}
