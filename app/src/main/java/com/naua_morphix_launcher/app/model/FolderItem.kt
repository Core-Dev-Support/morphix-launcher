package com.naua_morphix_launcher.app.model

data class FolderItem(
    val id: String,
    var name: String,
    val packageNames: MutableList<String> = mutableListOf(),
    var size: String = "REGULAR"
)

