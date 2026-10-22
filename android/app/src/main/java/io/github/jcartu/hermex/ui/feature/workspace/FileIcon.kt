package io.github.jcartu.hermex.ui.feature.workspace

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import io.github.jcartu.hermex.R

/**
 * Android vector counterpart of Pierre's `@pierre/trees` file icons selected by
 * `Features/Workspace/FileIcon.swift`. Exact names win, then longest extension.
 */
enum class FileIcon(@DrawableRes val drawable: Int) {
    AGENTS(R.drawable.file_icon_markdown),
    ASTRO(R.drawable.file_icon_html),
    BABEL(R.drawable.file_icon_javascript),
    BASH(R.drawable.file_icon_code),
    BIOME(R.drawable.file_icon_json),
    BROWSERSLIST(R.drawable.file_icon_text),
    BUN(R.drawable.file_icon_javascript),
    C(R.drawable.file_icon_code),
    CLAUDE(R.drawable.file_icon_markdown),
    CPP(R.drawable.file_icon_code),
    CSS(R.drawable.file_icon_css),
    DATABASE(R.drawable.file_icon_database),
    DOCKER(R.drawable.file_icon_docker),
    ESLINT(R.drawable.file_icon_javascript),
    FONT(R.drawable.file_icon_font),
    GIT(R.drawable.file_icon_git),
    GO(R.drawable.file_icon_code),
    GRAPHQL(R.drawable.file_icon_code),
    HTML(R.drawable.file_icon_html),
    IMAGE(R.drawable.file_icon_image),
    JAVASCRIPT(R.drawable.file_icon_javascript),
    JSON(R.drawable.file_icon_json),
    KOTLIN(R.drawable.file_icon_kotlin),
    MARKDOWN(R.drawable.file_icon_markdown),
    NEXTJS(R.drawable.file_icon_javascript),
    OXC(R.drawable.file_icon_json),
    PACKAGE(R.drawable.file_icon_json),
    PNPM(R.drawable.file_icon_javascript),
    POSTCSS(R.drawable.file_icon_css),
    PRETTIER(R.drawable.file_icon_javascript),
    PYTHON(R.drawable.file_icon_python),
    REACT(R.drawable.file_icon_javascript),
    README(R.drawable.file_icon_markdown),
    RUBY(R.drawable.file_icon_code),
    RUST(R.drawable.file_icon_code),
    SASS(R.drawable.file_icon_css),
    STYLELINT(R.drawable.file_icon_css),
    SVELTE(R.drawable.file_icon_html),
    SVG(R.drawable.file_icon_image),
    SVGO(R.drawable.file_icon_image),
    SWIFT(R.drawable.file_icon_swift),
    TABLE(R.drawable.file_icon_table),
    TAILWIND(R.drawable.file_icon_css),
    TERRAFORM(R.drawable.file_icon_code),
    TEXT(R.drawable.file_icon_text),
    TSCONFIG(R.drawable.file_icon_typescript),
    TYPESCRIPT(R.drawable.file_icon_typescript),
    VITE(R.drawable.file_icon_javascript),
    VSCODE(R.drawable.file_icon_code),
    VUE(R.drawable.file_icon_html),
    WASM(R.drawable.file_icon_code),
    WEBPACK(R.drawable.file_icon_javascript),
    YML(R.drawable.file_icon_code),
    ZIG(R.drawable.file_icon_code),
    ZIP(R.drawable.file_icon_archive),
    DEFAULT(R.drawable.file_icon_default),
    ;

    companion object {
        /** Port of `FileIcon.resolve(_:)`. */
        fun resolve(nameOrPath: String): FileIcon {
            var basename = nameOrPath.substringAfterLast('/')
                .replace(Regex(":\\d+(?::\\d+)?$"), "")
                .lowercase()
            ICON_BY_NAME[basename]?.let { return it }
            if (basename.startsWith("tsconfig.") && basename.endsWith(".json")) return TSCONFIG
            val segments = basename.split('.')
            for (index in 1 until segments.size) {
                ICON_BY_EXTENSION[segments.drop(index).joinToString(".")]?.let { return it }
            }
            return DEFAULT
        }

        private val ICON_BY_NAME = buildMap {
            listOf(".babelrc", ".babelrc.json", "babel.config.js", "babel.config.cjs", "babel.config.json", "babel.config.mjs").forEach { put(it, BABEL) }
            listOf(".bash_profile", ".bashrc", ".zprofile", ".zshenv", ".zshrc").forEach { put(it, BASH) }
            put(".browserslistrc", BROWSERSLIST)
            listOf(".dockerignore", "dockerfile", "compose.yaml", "compose.yml", "docker-compose.yaml", "docker-compose.yml", "docker-compose.override.yml").forEach { put(it, DOCKER) }
            listOf(".eslintignore", ".eslintrc", ".eslintrc.cjs", ".eslintrc.js", ".eslintrc.json", ".eslintrc.yaml", ".eslintrc.yml", "eslint.config.js", "eslint.config.cjs", "eslint.config.mjs", "eslint.config.mts", "eslint.config.ts").forEach { put(it, ESLINT) }
            listOf(".gitattributes", ".gitignore", ".gitkeep", ".gitmodules").forEach { put(it, GIT) }
            put(".oxlintrc.json", OXC)
            listOf(".postcssrc", ".postcssrc.json", ".postcssrc.yaml", ".postcssrc.yml", "postcss.config.js", "postcss.config.cjs", "postcss.config.mjs", "postcss.config.ts").forEach { put(it, POSTCSS) }
            listOf(".prettierignore", ".prettierrc", ".prettierrc.json", ".prettierrc.cjs", ".prettierrc.js", ".prettierrc.mjs", ".prettierrc.toml", ".prettierrc.yaml", ".prettierrc.yml", "prettier.config.js", "prettier.config.cjs", "prettier.config.mjs").forEach { put(it, PRETTIER) }
            listOf(".stylelintignore", ".stylelintrc", ".stylelintrc.cjs", ".stylelintrc.js", ".stylelintrc.json", ".stylelintrc.mjs", ".stylelintrc.yaml", ".stylelintrc.yml", "stylelint.config.js", "stylelint.config.cjs", "stylelint.config.mjs").forEach { put(it, STYLELINT) }
            put(".terraform.lock.hcl", TERRAFORM)
            put("agents.md", AGENTS)
            listOf("biome.json", "biome.jsonc").forEach { put(it, BIOME) }
            listOf("bun.lock", "bun.lockb", "bunfig.toml").forEach { put(it, BUN) }
            put("claude.md", CLAUDE)
            listOf("gemfile", "rakefile").forEach { put(it, RUBY) }
            listOf("next.config.js", "next.config.mjs", "next.config.mts", "next.config.ts").forEach { put(it, NEXTJS) }
            put("package.json", PACKAGE)
            listOf("pnpm-lock.yaml", "pnpm-workspace.yaml").forEach { put(it, PNPM) }
            put("readme.md", README)
            listOf("svgo.config.js", "svgo.config.cjs", "svgo.config.mjs", "svgo.config.ts").forEach { put(it, SVGO) }
            listOf("tailwind.config.js", "tailwind.config.cjs", "tailwind.config.mjs", "tailwind.config.ts").forEach { put(it, TAILWIND) }
            put("tsconfig.json", TSCONFIG)
            listOf("vite.config.js", "vite.config.mjs", "vite.config.mts", "vite.config.ts").forEach { put(it, VITE) }
            listOf("webpack.config.js", "webpack.config.babel.js", "webpack.config.cjs", "webpack.config.mjs", "webpack.config.ts").forEach { put(it, WEBPACK) }
        }

        private val ICON_BY_EXTENSION = mapOf(
            "7z" to ZIP, "bz2" to ZIP, "gz" to ZIP, "jar" to ZIP, "rar" to ZIP, "tar" to ZIP, "tgz" to ZIP, "zip" to ZIP,
            "astro" to ASTRO,
            "avif" to IMAGE, "bmp" to IMAGE, "gif" to IMAGE, "icns" to IMAGE, "ico" to IMAGE, "jpeg" to IMAGE, "jpg" to IMAGE, "png" to IMAGE, "webp" to IMAGE,
            "code-workspace" to VSCODE,
            "bash" to BASH, "fish" to BASH, "sh" to BASH, "zsh" to BASH,
            "c" to C, "h" to C,
            "cc" to CPP, "cpp" to CPP, "cxx" to CPP, "hh" to CPP, "hpp" to CPP, "hxx" to CPP, "inl" to CPP,
            "css" to CSS, "less" to CSS, "postcss" to CSS,
            "csv" to TABLE, "tsv" to TABLE,
            "cts" to TYPESCRIPT, "mts" to TYPESCRIPT, "ts" to TYPESCRIPT,
            "db" to DATABASE, "sql" to DATABASE, "sqlite" to DATABASE, "sqlite3" to DATABASE,
            "env" to TEXT, "env.development" to TEXT, "env.local" to TEXT, "env.production" to TEXT, "ini" to TEXT, "txt" to TEXT,
            "eot" to FONT, "woff" to FONT, "woff2" to FONT,
            "erb" to RUBY, "rake" to RUBY, "rb" to RUBY,
            "go" to GO,
            "gql" to GRAPHQL, "graphql" to GRAPHQL,
            "htm" to HTML, "html" to HTML,
            "js" to JAVASCRIPT, "mjs" to JAVASCRIPT,
            "json" to JSON, "jsonc" to JSON,
            "jsx" to REACT, "tsx" to REACT,
            "kt" to KOTLIN, "kts" to KOTLIN,
            "md" to MARKDOWN, "mdx" to MARKDOWN, "mdx.tsx" to MARKDOWN,
            "py" to PYTHON, "pyi" to PYTHON, "pyw" to PYTHON, "pyx" to PYTHON,
            "rs" to RUST,
            "sass" to SASS, "scss" to SASS,
            "svelte" to SVELTE,
            "svg" to SVG,
            "swift" to SWIFT,
            "tf" to TERRAFORM, "tfstate" to TERRAFORM, "tfvars" to TERRAFORM,
            "vue" to VUE,
            "wasm" to WASM,
            "yaml" to YML, "yml" to YML,
            "zig" to ZIG,
        )
    }
}

/** File-type vector used in tree, Git, and workspace references. */
@Composable
fun FileTypeIcon(path: String, modifier: Modifier = Modifier) {
    val icon = FileIcon.resolve(path)
    Image(painter = painterResource(icon.drawable), contentDescription = null, modifier = modifier)
}
