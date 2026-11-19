package com.diffuse.drive

/**
 * In-memory [DriveApi]: keeps the files "on Drive" so a test can run a sink twice against
 * the same state (as two backup runs would), and records what each call did.
 */
class FakeDrive : DriveApi {
    /** Live files, in creation order (as [listFiles] returns them). */
    val files = mutableListOf<DriveFile>()
    val folders = mutableListOf<String>()          // "name@parent"
    val created = mutableListOf<String>()          // "name@parent len=N"
    val contentUpdates = mutableListOf<String>()   // file ids
    val tagged = mutableListOf<String>()           // file ids
    private val folderIds = HashMap<String, String>()
    private var seq = 0

    override fun ensureFolder(name: String, parentId: String?): String =
        folderIds.getOrPut("$parentId/$name") { folders += "$name@$parentId"; "folder${seq++}" }

    override fun listFiles(): List<DriveFile> = files.toList()

    override fun create(
        name: String,
        parentId: String,
        mimeType: String,
        appProperties: Map<String, String>,
        content: UploadContent,
    ): String {
        val bytes = content.open().use { it.readBytes() }
        check(bytes.size.toLong() == content.length) { "declared ${content.length}, streamed ${bytes.size}" }
        val id = "file${seq++}"
        files += DriveFile(id, name, appProperties)
        created += "$name@$parentId len=${content.length}"
        return id
    }

    override fun updateContent(
        fileId: String,
        mimeType: String,
        appProperties: Map<String, String>,
        content: UploadContent,
    ) {
        val i = files.indexOfFirst { it.id == fileId }
        if (i < 0) throw DriveHttpException(404, "notFound", "File not found: $fileId")
        files[i] = files[i].copy(appProperties = files[i].appProperties + appProperties)
        contentUpdates += fileId
    }

    override fun setAppProperties(fileId: String, appProperties: Map<String, String>) {
        val i = files.indexOfFirst { it.id == fileId }
        files[i] = files[i].copy(appProperties = files[i].appProperties + appProperties)
        tagged += fileId
    }
}
