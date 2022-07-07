package sschr15.tools.remapper

import net.fabricmc.mapping.tree.TinyMappingFactory
import net.fabricmc.mapping.tree.TinyTree
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.io.path.*
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.size != 2) {
        println("Requires two args: file or dir, mapping file")
        exitProcess(1)
    }

    val path = Path(args[0]).toAbsolutePath()
    val mappingFile = Path(args[1]).toAbsolutePath()
    val firstLine = mappingFile.readLines().first()
    val isHashed = firstLine.contains("hashed")
    val mapping = mappingFile.bufferedReader().use(TinyMappingFactory::loadWithDetection)

    val backup = with(path) {
        if (isDirectory()) resolveSibling("$name-backup")
        else resolveSibling("$nameWithoutExtension-backup.$extension")
    }

    backup.deleteRecursivelyIfNeeded()
    path.copyToRecursivelyIfNeeded(backup)
    println("Backup created: $backup")

    val result = Files.walk(path).use { p -> p.map { recurse(it, mapping, isHashed) }.toList().any { it } }

    if (result) {
        println("One or more annotations were remapped")
    } else {
        println("No remapping occurred - restoring backup")
        path.deleteRecursivelyIfNeeded()
        backup.copyToRecursivelyIfNeeded(path)
        backup.deleteRecursivelyIfNeeded()
    }
}

/**
 * @return whether any remapping was done
 */
private fun recurse(path: Path, mapping: TinyTree, isHashed: Boolean, output: JarOutputStream? = null): Boolean = when (path.extension) {
    "jar" -> {
        val temp = createTempFile(suffix = ".jar")
        val result = temp.outputStream().use { os -> JarOutputStream(os).use { jos ->
            FileSystems.newFileSystem(path).use { fs ->
                Files.walk(fs.getPath("/")).use { p -> p.map { recurse(it, mapping, isHashed, jos) }.toList().any { it } }
            }
        } }
        if (!result) {
            if (output != null) {
                output.putNextEntry(ZipEntry(path.absoluteWithoutLeadingSlash))
                output.write(temp.readBytes())
                output.closeEntry()
            }
            false
        } else {
            if (output != null) {
                output.putNextEntry(ZipEntry(path.absoluteWithoutLeadingSlash))
                output.write(temp.readBytes())
                output.closeEntry()
            } else {
                temp.copyTo(path, overwrite = true)
            }

            true
        }.also { temp.deleteExisting() }
    }
    "class" -> {
        val out = remap(path, mapping, isHashed, output)
        if (!out && output != null) {
            output.putNextEntry(ZipEntry(path.absoluteWithoutLeadingSlash))
            output.write(path.readBytes())
            output.closeEntry()
        }
        out
    }
    else -> {
        if (output != null && path.isRegularFile()) {
            output.putNextEntry(ZipEntry(path.absoluteWithoutLeadingSlash))
            output.write(path.readBytes())
            output.closeEntry()
        }
        false
    }
}

/**
 * @return whether any remapping was done (in case nothing changes, it doesn't copy files without a reason)
 */
private fun remap(path: Path, mapping: TinyTree, isHashed: Boolean, output: JarOutputStream?): Boolean {
    val node = ClassNode()
    path.inputStream().use { ClassReader(it).accept(node, 0) }

    val annotations = node.visibleAnnotations ?: return false
    val metadata = annotations.find { it.desc == "Lkotlin/Metadata;" } ?: return false

    val d2Idx = metadata.values.indexOf("d2") + 1

    @Suppress("UNCHECKED_CAST")
    val d2 = metadata.values[d2Idx] as? List<String> ?: return false

    val d2Mapped = d2.map { remapOne(it, mapping, isHashed) }

    if (d2Mapped == d2) return false

    metadata.values[d2Idx] = d2Mapped

    val writer = ClassWriter(0)
    node.accept(writer)
    val bytes = writer.toByteArray()

    if (output != null) {
        output.putNextEntry(ZipEntry(path.absoluteWithoutLeadingSlash))
        output.write(bytes)
        output.closeEntry()
    } else {
        path.deleteExisting()
        path.writeBytes(bytes)
    }

    return true
}

private fun remapOne(mapping: String, mappingTree: TinyTree, isHashed: Boolean): String {
    var newSig = mapping
    while (newSig.contains("net/minecraft/" + if (isHashed) "unmapped/C_" else "class_")) {
        val firstUnmappedIdx = newSig.indexOf("net/minecraft/" + if (isHashed) "unmapped/C_" else "class_")
        val lastUnmappedIdx = newSig.indexOf(';', firstUnmappedIdx)
        val unmapped = newSig.substring(firstUnmappedIdx until lastUnmappedIdx)
        val mapped = mappingTree.classes.find { it.getName(if (isHashed) "hashed" else "intermediary") == unmapped }
            ?.getRawName("named")?.takeIf(String::isNotEmpty) ?: unmapped.replace("net", "ne\u0000t")
        // the null byte is to prevent infinite loops
        // because either the class wasn't found or the class was found but was unmapped
        // and since this loop checks for unmapped classes, it could loop infinitely
        newSig = newSig.replace(unmapped, mapped)
    }
    return newSig.replace("\u0000", "") // remove any added null bytes
}

private fun Path.deleteRecursivelyIfNeeded() {
    if (exists()) {
        if (isDirectory()) toFile().deleteRecursively()
        else deleteExisting()
    }
}

private fun Path.copyToRecursivelyIfNeeded(other: Path) {
    if (isDirectory()) toFile().copyRecursively(other.toFile())
    else copyTo(other)
}

private val Path.absoluteWithoutLeadingSlash
    get() = toAbsolutePath().toString().removePrefix("/")
