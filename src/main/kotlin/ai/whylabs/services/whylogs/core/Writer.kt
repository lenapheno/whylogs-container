package ai.whylabs.services.whylogs.core

import ai.whylabs.service.invoker.ApiException
import ai.whylabs.service.model.SegmentTag
import com.github.michaelbull.retry.policy.RetryPolicy
import com.github.michaelbull.retry.policy.fullJitterBackoff
import com.github.michaelbull.retry.policy.limitAttempts
import com.github.michaelbull.retry.policy.plus
import com.github.michaelbull.retry.retry
import com.whylogs.core.DatasetProfile
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.FileChannel
import java.nio.file.Files

interface Writer {
    suspend fun write(profile: DatasetProfile, orgId: String, datasetId: String)
}

private val retryPolicy: RetryPolicy<Throwable> = limitAttempts(3) + fullJitterBackoff(base = 10, max = 5_000)

class SongbirdWriter : Writer {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val songbirdClientManager = SongbirdClientManager()

    override suspend fun write(profile: DatasetProfile, orgId: String, datasetId: String) {
        val tags = profile.tags
            .filterKeys { it.startsWith(SegmentTagPrefix) }
            .map { tag ->
                SegmentTag().apply {
                    key = tag.key.substring(SegmentTagPrefix.length)
                    value = tag.value
                }
            }

        try {
            // TODO Delete this block once we can use our newer api.
            "Use the old API".let {
                val tempFile = Files.createTempFile("whylogs", "profile")
                Files.newOutputStream(tempFile).use {
                    profile.toProtobuf().build().writeDelimitedTo(it)
                }

                retry(retryPolicy) {
                    songbirdClientManager.logApi.log(
                        orgId,
                        datasetId,
                        profile.dataTimestamp.toEpochMilli(),
                        tags,
                        null,
                        tempFile.toFile()
                    )
                }
            }

            // TODO uncomment to switch over to the newer api once we finish it.
//            val uploadResponse = retry(retryPolicy) {
//                songbirdClientManager.logApi.logAsync(
//                    orgId,
//                    datasetId,
//                    profile.dataTimestamp.toEpochMilli(),
//                    tags,
//                    null
//                )
//            }
//
//            retry(retryPolicy) {
//                uploadToUrl(uploadResponse.uploadUrl!!, profile)
//            }
//
            val tagString = if (tags.isEmpty()) "NO_TAGS" else tags.joinToString(",") { "[${it.key}=${it.value}]" }
            logger.info("Pushed ${profile.tags[DatasetIdTag]}/${tagString}/${profile.dataTimestamp} data successfully")
        } catch (e: ApiException) {
            logger.error("Bad request when sending data to WhyLabs. Code: ${e.code}. Message: ${e.responseBody}", e)
            throw e
        } catch (t: Throwable) {
            logger.error("Fail to send data to WhyLabs", t)
            throw t
        }
    }
}

private fun uploadToUrl(url: String, profile: DatasetProfile) {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.doOutput = true
    connection.setRequestProperty("Content-Type", "application/octet-stream")
    connection.requestMethod = "PUT"

    connection.outputStream.use { out ->
        profile.toProtobuf().build().writeTo(out)
    }

    if (connection.responseCode != 200) {
        throw RuntimeException("Error uploading profile: ${connection.responseCode} ${connection.responseMessage}")
    }
}