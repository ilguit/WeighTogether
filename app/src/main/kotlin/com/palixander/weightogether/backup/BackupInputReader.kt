package com.palixander.weightogether.backup

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal fun readBackupJson(input: InputStream, byteLimit: Int): String {
    val bytes = try {
        val output = ByteArrayOutputStream(minOf(byteLimit, DEFAULT_BUFFER_SIZE))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (output.size() > byteLimit - count) throw BackupException.Limits("$", byteLimit)
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } catch (error: BackupException) {
        throw error
    } catch (error: IOException) {
        throw BackupException.Io(error)
    }
    val json = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (error: java.nio.charset.CharacterCodingException) {
        throw BackupException.Corrupt(error)
    }
    return json
}
