package com.limelight.nvstream.av

class ByteBufferDescriptor(
    var data: ByteArray,
    var offset: Int,
    var length: Int
) {
    var nextDescriptor: ByteBufferDescriptor? = null

    constructor(desc: ByteBufferDescriptor) : this(desc.data, desc.offset, desc.length)

    fun reinitialize(data: ByteArray, offset: Int, length: Int) {
        this.data = data
        this.offset = offset
        this.length = length
        this.nextDescriptor = null
    }

    fun print(offset: Int = this.offset, length: Int = this.length) {
        var i = offset
        while (i < offset + length) {
            if (i + 8 <= offset + length) {
                System.out.printf(
                    "%x: %02x %02x %02x %02x %02x %02x %02x %02x\n", i,
                    data[i], data[i + 1], data[i + 2], data[i + 3],
                    data[i + 4], data[i + 5], data[i + 6], data[i + 7]
                )
                i += 8
            } else {
                System.out.printf("%x: %02x \n", i, data[i])
                i++
            }
        }
        println()
    }
}
