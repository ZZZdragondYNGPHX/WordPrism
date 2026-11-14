package dev.wordprism.fixtures

import dev.wordprism.contract.*
import dev.wordprism.engine.Database
import dev.wordprism.engine.PackageFormat
import dev.wordprism.engine.PackageManifest
import java.io.File
import kotlinx.serialization.json.*

/** Documentation artifacts derived exclusively from Kotlin serialization descriptors. */
internal fun writeSchemas(directory: File) {
    directory.mkdirs()
    val documents = linkedMapOf<String, JsonObject>()
    mapOf("request" to Request.serializer().descriptor, "query" to Query.serializer().descriptor,
        "model-catalog-request" to ModelCatalogRequest.serializer().descriptor,
        "reply" to Reply.serializer().descriptor, "database" to Database.serializer().descriptor,
        "package-manifest" to PackageManifest.serializer().descriptor).forEach { (name, descriptor) ->
        documents[name] = JsonSchemas.document(descriptor, "WordPrism contract v1: $name")
    }
    PackageFormat.moduleFields.forEach { (name, fields) ->
        documents["module-$name"] = JsonSchemas.document(Database.serializer().descriptor, "WordPrism $name module v${PackageFormat.MODULE_VERSION}", rootFields = fields)
    }
    documents.forEach { (name, document) ->
        File(directory, "$name.schema.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), document) + "\n")
    }
}
