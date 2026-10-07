plugins { id("com.android.application") version "8.13.0" }

val craftName = providers.gradleProperty("craftApp").get()
val craftLabel = providers.gradleProperty("craftLabel").get()
val prepareCraftIcon = tasks.register<Copy>("prepareCraftIcon") {
    val sourceSvg = file("../../assets/app-icon/$craftName-small.svg")
    inputs.file(sourceSvg)
    outputs.dir(layout.buildDirectory.dir("craft-res"))
    from("../../assets/app-icon/hicolor/256x256/apps/ai.storyteller.$craftName.png")
    into(layout.buildDirectory.dir("craft-res/drawable"))
    rename { "craft_icon.png" }
    doLast {
        // The first path is the existing icon's silhouette; retain its original geometry.
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val svg = factory.newDocumentBuilder().parse(sourceSvg)
        val silhouette = svg.getElementsByTagName("path").item(0) as org.w3c.dom.Element
        val path = silhouette.getAttribute("d").replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
        val rectangles = svg.getElementsByTagName("rect")
        val background = (0 until rectangles.length).map { rectangles.item(it) as org.w3c.dom.Element }.first { it.hasAttribute("fill") }.getAttribute("fill")
        require(background.matches(Regex("#[0-9a-fA-F]{6}")))
        val res = layout.buildDirectory.dir("craft-res").get().asFile
        file("$res/drawable/craft_monochrome.xml").writeText("""<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="512" android:viewportHeight="512"><path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="$path"/></vector>""")
        file("$res/values").mkdirs()
        file("$res/values/craft-icon.xml").writeText("""<resources><color name="craft_icon_background">$background</color></resources>""")
    }
}
tasks.named("preBuild") { dependsOn(prepareCraftIcon) }
android {
    namespace = "ai.craft.android"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "io.github.tqmane.$craftName"
        ndk.abiFilters += providers.gradleProperty("craftAbis").getOrElse("arm64-v8a").split(",")
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = Regex("(?m)^version\\s*=\\s*\"([^\"]+)\"").find(file("../../Cargo.toml").readText())!!.groupValues[1] + "-android.1"
        testInstrumentationRunner = "ai.craft.android.InputInstrumentation"
        manifestPlaceholders["craftLibrary"] = craftName
        buildConfigField("String", "CRAFT_LIBRARY", "\"$craftName\"")
        resValue("string", "app_name", craftLabel)
    }
    sourceSets["main"].apply {
        manifest.srcFile("AndroidManifest.xml")
        java.srcDirs("src")
        res.srcDirs("res", "build/craft-res")
        assets.srcDirs("build/craft-assets")
    }
    sourceSets["debug"].jniLibs.srcDirs("build/rust")
    sourceSets["release"].jniLibs.srcDirs("build/rust-release")
    val signingPath = providers.environmentVariable("CRAFT_ANDROID_KEYSTORE").orNull
    if (signingPath != null) {
        val distribution = signingConfigs.create("distribution") {
            storeFile = file(signingPath)
            storePassword = providers.environmentVariable("CRAFT_ANDROID_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("CRAFT_ANDROID_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("CRAFT_ANDROID_KEY_PASSWORD").orNull ?: storePassword
        }
        buildTypes["release"].signingConfig = distribution
    }
    buildTypes["release"].isDebuggable = false
    testBuildType = providers.gradleProperty("craftTestBuildType").getOrElse("debug")
    sourceSets["androidTest"].java.srcDirs("tests/android")
    packaging { jniLibs.useLegacyPackaging = false }
    buildFeatures.buildConfig = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
// Must match android-activity 0.6.1. Its Rust build compiles the native glue; no prefab copy.
dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.8.22"))
    implementation("androidx.games:games-activity:4.4.0")
    // GameActivity's published POM does not declare the classes it extends.
    implementation("androidx.appcompat:appcompat:1.7.1")
}
