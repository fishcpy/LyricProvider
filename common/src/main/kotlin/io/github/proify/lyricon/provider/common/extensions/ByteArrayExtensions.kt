/*
 * Copyright 2026 Proify, Tomakino
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.proify.lyricon.provider.common.extensions

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * ZLIB压缩字节数组
 */
fun ByteArray.deflate(): ByteArray {
    if (isEmpty()) return byteArrayOf()

    return Deflater().run {
        setInput(this@deflate)
        finish()

        ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(4096)
            while (!finished()) {
                output.write(buffer, 0, deflate(buffer))
            }
            output.toByteArray()
        }.also { end() }
    }
}

/**
 * ZLIB解压字节数组
 */
fun ByteArray.inflate(): ByteArray {
    if (isEmpty()) return byteArrayOf()

    return Inflater().run {
        setInput(this@inflate)

        ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(4096)
            while (!finished()) {
                val count = inflate(buffer)
                if (count == 0 && needsInput()) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }.also { end() }
    }
}