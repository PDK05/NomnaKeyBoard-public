import org.gradle.internal.os.OperatingSystem

plugins {
    id("com.android.application")
}

// HANNOM_BUILD_DIR (nếu có): để thư mục build ngoài repo, vd. sang ổ khác khi ổ chứa repo hết chỗ.
System.getenv("HANNOM_BUILD_DIR")?.let { layout.buildDirectory.set(File(it, "app")) }

android {
    namespace = "com.hannom.ime"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.hannom.ime"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.1.6"
        ndk {
            abiFilters += rustTargets.keys
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Ký bằng khoá debug như bản thử trước đây: cài đè lên bản đang dùng, không mất từ điển / dữ liệu đã học.
            // Bản release không debuggable nên ART biên dịch mã sẵn, khởi động nhanh hơn nhiều.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Phông Hán Nôm ~20 MB: để nguyên không nén cho Android đọc thẳng từ APK.
    androidResources {
        noCompress += listOf("ttf", "otf")
    }
}

// ---- Lõi Rust (android-ffi) → src/main/jniLibs/<abi>/libhannom_jni.so ----

val rustTargets: Map<String, String>
    get() = mapOf("arm64-v8a" to "aarch64-linux-android", "x86_64" to "x86_64-linux-android")

val repoRoot: File = rootDir.resolve("../..").canonicalFile
// Thư mục build của Cargo: CARGO_TARGET_DIR nếu có (máy dev để trên ổ D), không thì target/ của repo.
val cargoTarget: File = System.getenv("CARGO_TARGET_DIR")?.let(::File) ?: repoRoot.resolve("target")
val cargo: String = System.getenv("CARGO_HOME")?.let { "$it/bin/cargo" } ?: "cargo"
val ndkHost = when {
    OperatingSystem.current().isWindows -> "windows-x86_64"
    OperatingSystem.current().isMacOsX -> "darwin-x86_64"
    else -> "linux-x86_64"
}
val ndkDir = androidComponents.sdkComponents.ndkDirectory
val minSdkForLinker = 26

val copyRustLibs = tasks.register("copyRustLibs")

rustTargets.forEach { (abi, triple) ->
    val build = tasks.register<Exec>("cargoBuild-$abi") {
        group = "rust"
        workingDir = repoRoot
        commandLine(cargo, "build", "--release", "-p", "hannom-jni", "--target", triple)
        inputs.dir(repoRoot.resolve("core/src"))
        inputs.dir(repoRoot.resolve("handwriting/src"))
        inputs.dir(repoRoot.resolve("android-ffi/src"))
        outputs.file(cargoTarget.resolve("$triple/release/libhannom_jni.so"))
        doFirst {
            val suffix = if (OperatingSystem.current().isWindows) ".cmd" else ""
            val linker = ndkDir.get().asFile
                .resolve("toolchains/llvm/prebuilt/$ndkHost/bin/$triple$minSdkForLinker-clang$suffix")
            environment("CARGO_TARGET_${triple.uppercase().replace('-', '_')}_LINKER", linker.absolutePath)
        }
    }
    val copy = tasks.register<Copy>("copyRustLib-$abi") {
        dependsOn(build)
        from(cargoTarget.resolve("$triple/release/libhannom_jni.so"))
        into(layout.projectDirectory.dir("src/main/jniLibs/$abi"))
    }
    copyRustLibs.configure { dependsOn(copy) }
}

// Mọi từ điển nằm ở dict/ của repo: Hán Nôm (nomna-dict.txt), gợi ý Quốc ngữ (vi-dict.txt),
// gợi ý tiếng Anh (en-dict.txt, thiếu thì tiếng Anh không gợi ý).
val copyDictionary = tasks.register<Copy>("copyDictionary") {
    from(repoRoot.resolve("dict")) { include("nomna-dict.txt", "vi-dict.txt", "en-dict.txt") }
    into(layout.projectDirectory.dir("src/main/assets/dict"))
    rename { name ->
        when (name) {
            "nomna-dict.txt" -> "builtin.txt"
            else -> name.removeSuffix("-dict.txt") + ".txt"
        }
    }
}

// Phông Hán Nôm và phông dự phòng cho chữ nó thiếu (không có trên git, xem fonts/README.md);
// thiếu thì dùng phông của máy. MiSans giữ đúng tên gốc "MiSans L3.ttf"; GitHub Release đổi dấu cách thành dấu
// chấm nên nhận cả "MiSans.L3.ttf".
val copyFont = tasks.register<Copy>("copyFont") {
    from(repoRoot.resolve("fonts")) {
        include("MiSans L3.ttf", "MiSans.L3.ttf", "hannom-extra.ttf")
        rename("MiSans\\.L3\\.ttf", "MiSans L3.ttf")
    }
    into(layout.projectDirectory.dir("src/main/assets/fonts"))
}

// Mô hình viết tay (không có trên git, xem models/README.md); thiếu thì bàn phím viết tay không gợi ý.
val copyModel = tasks.register<Copy>("copyModel") {
    from(repoRoot.resolve("models")) { include("hw.bin") }
    into(layout.projectDirectory.dir("src/main/assets/models"))
}

tasks.named("preBuild") {
    dependsOn(copyRustLibs, copyDictionary, copyFont, copyModel)
}
