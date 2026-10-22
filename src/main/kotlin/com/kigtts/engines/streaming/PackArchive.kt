package com.kigtts.engines.streaming

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** Bounded extraction into a fresh plugin-owned stage; never follows archive links. */
internal object PackArchive {
    private const val LIMIT = 2L * 1024 * 1024 * 1024
    fun extract(source: File, stage: File) {
        require(source.isFile && source.length() in 1..LIMIT)
        val seen = mutableSetOf<String>(); var total = 0L
        fun entry(name: String, size: Long, directory: Boolean, input: InputStream) {
            val normalized = name.removePrefix("./").removeSuffix("/")
            val target = safePackFile(stage, normalized)
            require(seen.add(normalized) && seen.size <= 10_000) { "重复路径或过多文件" }
            require(size in 0..LIMIT && total + size <= LIMIT) { "识别包解压大小超过 2 GiB" }; total += size
            if (directory) require(target.mkdirs() || target.isDirectory)
            else {
                require(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                target.outputStream().use { output ->
                    val bytes = ByteArray(64 * 1024); var copied = 0L
                    while (true) {
                        val count = input.read(bytes); if (count < 0) break
                        copied += count; require(copied <= size) { "解压长度超出声明" }; output.write(bytes, 0, count)
                    }
                    require(copied == size) { "识别包文件不完整" }
                }
            }
        }
        val magic = ByteArray(4).also { bytes -> source.inputStream().use { input ->
            var offset = 0
            while (offset < bytes.size) { val count = input.read(bytes, offset, bytes.size - offset); if (count < 0) break; offset += count }
        } }
        if (magic.size >= 2 && magic[0] == 0x50.toByte() && magic[1] == 0x4b.toByte()) ZipFile(source).use { zip ->
            zip.entries().asSequence().forEach { file -> zip.getInputStream(file).use { entry(file.name, file.size, file.isDirectory, it) } }
        } else source.inputStream().buffered().use { raw ->
            val decompressed = when {
                magic.size >= 3 && String(magic, 0, 3) == "BZh" -> BZip2CompressorInputStream(raw)
                magic.size >= 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte() -> GzipCompressorInputStream(raw)
                else -> error("支持 .kigasr、ZIP、tar.bz2 和 tar.gz")
            }
            TarArchiveInputStream(decompressed).use { tar ->
                while (true) {
                    val file = tar.nextEntry ?: break
                    require((file.isFile || file.isDirectory) && !file.isSparse && !file.isSymbolicLink && !file.isLink) { "识别包不能含链接或特殊文件" }
                    entry(file.name, file.size, file.isDirectory, tar)
                }
            }
        }
    }
}
