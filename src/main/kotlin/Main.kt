import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.util.Base64
import java.io.File
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.cdimascio.dotenv.dotenv
import org.jsoup.nodes.Document

val projectDir = File("").absoluteFile
val logFile = projectDir.resolve("application.log")

fun main() {

    //env
    val dotenv = dotenv {
        ignoreIfMissing = true
    }
    val username = dotenv["CONFLUENCE_USERNAME"]
    val password = dotenv["CONFLUENCE_PASSWORD"]
    val confluenceBaseUrl = dotenv["CONFLUENCE_BASE_URL"]

    val dataFolder = projectDir.resolve("Data")
    val spaceKey = "SUP" // Ключ пространства
    val rootParentPageId = "73314635" // можно оставить пустым

    if (!logFile.exists()) {logFile.createNewFile() }

    if (!dataFolder.exists()) {
        println("❌ Папка Data не найдена!")
        logFile.appendText("❌ Папка Data не найдена!\n")
        return
    }

    // Получаем маппинг: файл -> уникальное имя (Одинаковые имена не импортируются в Confluence)
    val docxFiles = getAllDocxFiles(dataFolder)
    val titleMap = generateUniqueTitles(docxFiles)
    titleMap.forEach { (file, newName) ->
        println("📄 ${file.name} → '$newName'")
        logFile.appendText("📄 ${file.name} → '$newName'\n")
    }

    uploadDirectoryRecursively(
        folder = dataFolder,
        baseUrl = confluenceBaseUrl,
        spaceKey = spaceKey,
        parentPageId = rootParentPageId,
        username = username,
        password = password,
        titleMap = titleMap
    )
}

fun replaceImagesWithAcTags(htmlFile: File): Pair<String, List<File>> {
    val doc = Jsoup.parse(htmlFile, "UTF-8")
    val images = doc.select("img[src]")
    val attachments = mutableListOf<File>()

    val mediaDir = projectDir.resolve("media").resolve("media")

    for (img in images) {
        val src = img.attr("src")
        val fileName = File(src).name
        val imageFile = File(mediaDir, fileName)
        if (imageFile.exists()) {
            attachments.add(imageFile)

            // Заменяем <img> на <ac:image><ri:attachment/></ac:image>
            val acImage = doc.createElement("ac:image")
            val riAttachment = doc.createElement("ri:attachment")
            riAttachment.attr("ri:filename", imageFile.name)
            acImage.appendChild(riAttachment)
            img.replaceWith(acImage)
        } else {
            println("⚠️ Картинка не найдена: $src")
            logFile.appendText("⚠️ Картинка не найдена: $src\n")
        }
    }
    doc.outputSettings(Document.OutputSettings().syntax(Document.OutputSettings.Syntax.xml))
    return doc.body().html() to attachments
}

fun uploadAttachment(baseUrl: String, pageId: String, image: File, username: String, password: String) {
    val client = OkHttpClient()
    val auth = Base64.getEncoder().encodeToString("$username:$password".toByteArray())

    val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("file", image.name, image.readBytes().toRequestBody("application/octet-stream".toMediaTypeOrNull()))
        .build()

    val request = Request.Builder()
        .url("$baseUrl/rest/api/content/$pageId/child/attachment")
        .addHeader("Authorization", "Basic $auth")
        .addHeader("X-Atlassian-Token", "no-check")
        .post(requestBody)
        .build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "No body"
            println("❌ Ошибка загрузки вложения ${image.name}: ${response.code} — $errorBody")
            logFile.appendText("❌ Ошибка загрузки вложения ${image.name}: ${response.code} — $errorBody\n")
        } else {
            println("✅ Вложение загружено: ${image.name}")
            logFile.appendText("✅ Вложение загружено: ${image.name}\n")
        }
    }
}

fun convertDocxWithPandoc(docxFile: File): File {
    val htmlFile = File(docxFile.parent, docxFile.nameWithoutExtension + ".html")
    val process = ProcessBuilder(
        "pandoc", docxFile.absolutePath, "-f", "docx", "-t", "html", "-o", htmlFile.absolutePath, "--extract-media=media"
    ).start()
    process.waitFor()
    return htmlFile
}

fun uploadPageAndAttachments(
    baseUrl: String,
    spaceKey: String,
    title: String,
    htmlContent: String,
    attachments: List<File>,
    username: String,
    password: String,
    parentPageId: String?
    ): String? {
    val client = OkHttpClient()
    val auth = Base64.getEncoder().encodeToString("$username:$password".toByteArray())

    val createJson = """
        {
          "type": "page",
          "title": "$title",
          "space": { "key": "$spaceKey" },
          ${if (!parentPageId.isNullOrEmpty()) """
          "ancestors": [{ "id": $parentPageId }],
          """ else ""}
          "body": {
            "storage": {
              "value": "<p>Временное тело</p>",
              "representation": "storage"
            }
          }
        }
    """.trimIndent()

    val request = Request.Builder()
        .url("$baseUrl/rest/api/content")
        .addHeader("Authorization", "Basic $auth")
        .addHeader("Content-Type", "application/json")
        .post(createJson.toRequestBody("application/json".toMediaTypeOrNull()))
        .build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "No body"
            println("❌ Ошибка создания страницы: ${response.code} — $errorBody")
            logFile.appendText("❌ Ошибка создания страницы: ${response.code} — $errorBody\n")
            return null
        }
        val responseBody = response.body?.string() ?: ""
        val pageId = Regex("\"id\"\\s*:\\s*\"(\\d+)\"").find(responseBody)?.groupValues?.get(1)
        if (pageId == null) {
            println("❌ Не удалось получить ID страницы.")
            logFile.appendText("❌ Не удалось получить ID страницы.\n")
            return null
        }
        // Загрузка вложений
        for (img in attachments) {
            uploadAttachment(baseUrl, pageId, img, username, password)
        }
        // Обновление страницы с телом
        val updateJson = """
            {
              "version": { "number": 2 },
              "title": "$title",
              "type": "page",
              "body": {
                "storage": {
                  "value": ${jacksonObjectMapper().writeValueAsString(htmlContent)},
                  "representation": "storage"
                }
              }
            }
        """.trimIndent()

        val updateRequest = Request.Builder()
            .url("$baseUrl/rest/api/content/$pageId")
            .addHeader("Authorization", "Basic $auth")
            .addHeader("Content-Type", "application/json")
            .put(updateJson.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        client.newCall(updateRequest).execute().use { updateResp ->
            if (!updateResp.isSuccessful) {
                println("Отправляемый Json на обновление:\n" + updateJson)
                logFile.appendText("Отправляемый Json на обновление:\n" + updateJson+"\n")
                println("❌ Ошибка обновления страницы: ${updateResp.code} — ${updateResp.body?.string()}")
                logFile.appendText("❌ Ошибка обновления страницы: ${updateResp.code} — ${updateResp.body?.string()}\n")
                // Очистка папки с media после загрузки страницы
                clearDirectory(projectDir.resolve("media").resolve("media"))
                return null
            } else {
                println("✅ Страница обновлена с вложениями: $title")
                logFile.appendText("✅ Страница обновлена с вложениями: $title\n")
                // Очистка папки с media после загрузки страницы
                clearDirectory(projectDir.resolve("media").resolve("media"))
                return pageId
            }
        }
    }
}

fun uploadDirectoryRecursively(
    folder: File,
    baseUrl: String,
    spaceKey: String,
    parentPageId: String?,
    username: String,
    password: String,
    titleMap: Map<File, String>
) {
    // Сначала обработаем .docx файлы в этой папке
    val filesInFolder = folder.listFiles().orEmpty()
    for (file in filesInFolder) {
        if (file.isFile && file.extension.equals("docx", ignoreCase = true)) {
            val uniqueTitle = titleMap[file] ?: file.nameWithoutExtension

            val htmlFile = convertDocxWithPandoc(file)
            if (!htmlFile.exists()) {
                println("❌ Не удалось создать HTML для ${file.name}")
                logFile.appendText("❌ Не удалось создать HTML для ${file.name}\n")
                continue
            }

            val (htmlContent, attachments) = replaceImagesWithAcTags(htmlFile)

            val pageId = uploadPageAndAttachments(
                baseUrl = baseUrl,
                spaceKey = spaceKey,
                title = uniqueTitle,
                htmlContent = htmlContent,
                attachments = attachments,
                username = username,
                password = password,
                parentPageId = parentPageId
            )

            // Обработка вложенной папки с тем же именем
            val folderWithSameName = File(folder, "${file.nameWithoutExtension}")
            if (folderWithSameName.exists() && folderWithSameName.isDirectory) {
                uploadDirectoryRecursively(
                    folder = folderWithSameName,
                    baseUrl = baseUrl,
                    spaceKey = spaceKey,
                    parentPageId = pageId,
                    username = username,
                    password = password,
                    titleMap = titleMap
                )
            }
        }
    }
}

fun clearDirectory(dir: File) {
    if (!dir.exists()) return

    dir.listFiles()?.forEach { file ->
        if (file.isDirectory) {
            clearDirectory(file) // рекурсивная очистка подпапок
            file.delete() // удаление самой папки (после очистки)
        } else {
            if (file.delete()) {
                println("🗑️ Удалён файл: ${file.name}")
                logFile.appendText("🗑️ Удалён файл: ${file.name}\n")
            } else {
                println("❌ Не удалось удалить файл: ${file.name}")
                logFile.appendText("❌ Не удалось удалить файл: ${file.name}\n")
            }
        }
    }
}

fun getAllDocxFiles(folder: File): List<File> {
    val result = mutableListOf<File>()

    fun recursiveSearch(currentFolder: File) {
        currentFolder.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                recursiveSearch(file)
            } else if (file.extension.equals("docx", ignoreCase = true)) {
                result.add(file)
            }
        }
    }

    recursiveSearch(folder)
    return result
}

fun generateUniqueTitles(docxFiles: List<File>): Map<File, String> {
    val nameCount = mutableMapOf<String, Int>()
    val result = mutableMapOf<File, String>()

    // Подсчёт количества повторений
    for (file in docxFiles) {
        val baseName = file.nameWithoutExtension
        nameCount[baseName] = nameCount.getOrDefault(baseName, 0) + 1
    }

    // Генерация уникальных имён
    val usedNames = mutableMapOf<String, Int>()
    for (file in docxFiles) {
        val baseName = file.nameWithoutExtension
        val count = nameCount[baseName]!!
        if (count > 1) {
            val suffix = usedNames.getOrDefault(baseName, 0) + 1
            usedNames[baseName] = suffix
            result[file] = "$baseName $suffix"
        } else {
            result[file] = baseName
        }
    }

    return result
}