package io.github.gretchycat.infiniscribe

/** Canonical text-reader associations, shared with hosts through handler descriptors. */
object ScribeTextFormats {
    val MARKDOWN_EXTENSIONS = setOf(
        "md", "markdown", "mdown", "mkd", "mkdn"
    )
    val HTML_EXTENSIONS = setOf(
        "html", "htm", "xhtml"
    )
    val TEXT_EXTENSIONS = setOf(
        "diz", "asc", "nfo", "ans", "ansi", "txt", "log", "cfg", "conf", "ini", "properties", "sh", "bash", "zsh", "fish",
        "py", "kt", "kts", "java", "c", "cpp", "cc", "cxx", "h", "hpp", "cs", "go", "rs",
        "rb", "php", "js", "mjs", "cjs", "ts", "tsx", "jsx", "json", "xml", "yaml", "yml",
        "toml", "sql", "env", "diff", "patch", "bat", "cmd", "ps1",
        "gradle", "lua", "r", "swift", "dart", "asm", "s", "pl", "pm", "tcl", "dockerfile",
        "makefile", "mk", "cmake", "groovy", "gitignore", "gitattributes", "editorconfig", "bashrc", "zshrc", "profile",
        "ada", "adb", "ads", "bas", "bi", "cbl", "clj", "cljc", "cljs",
        "cob", "csh", "css", "cts", "el", "erl", "ex", "exs", "f", "f03",
        "f08", "f90", "f95", "for", "fs", "fsx", "hh", "hp", "hrl", "hs",
        "hxx", "inl", "ipp", "jl", "jsonc", "ksh", "less", "lhs", "lisp", "lsp",
        "mak", "mts", "nim", "nims", "pas", "perl", "php3", "php4", "php5", "phps",
        "plist", "pp", "py2", "py3", "pyi", "pyw", "sass", "sc", "scala", "scm",
        "scss", "ss", "tcc", "tcsh", "tk", "vb", "vbs", "xsd", "xsl", "xslt"
    )
    val TEXT_MIME_TYPES = setOf(
        "text/x-ansi", "text/x-ascii-art", "text/x-diz", "text/x-nfo",
        "application/json", "application/xml", "application/x-yaml", "application/yaml",
        "application/javascript", "application/x-javascript", "application/x-sh",
        "application/x-csh", "application/x-python", "application/x-ruby",
        "application/ld+json", "application/toml", "application/ecmascript",
        "application/x-shellscript", "application/x-perl", "application/sql",
        "application/x-bat", "application/x-powershell", "application/x-fishscript",
        "application/x-php", "application/vnd.dart"
    )
}
