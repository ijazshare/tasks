import com.android.build.api.artifact.SingleArtifact

plugins {
    // AGP 9 compiles Kotlin itself, so there is no org.jetbrains.kotlin.android here.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.ijazshare.tasks"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        applicationId = "io.github.ijazshare.tasks"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // MigrationTestHelper reads the exported schemas (1.json, 2.json) as test assets.
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        compose = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            // Robolectric needs the merged resources and manifest (fonts, theme) for the screenshot tests.
            isIncludeAndroidResources = true
        }
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // Dependency bumps are deliberate catalog edits; "a newer version exists" must not fail builds.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    // Room writes schemas/<db class>/<version>.json here; the files are checked in.
    arg("room.schemaLocation", "$projectDir/schemas")
}

// The core app is offline by design: no INTERNET permission, ever (spec section 12).
// Fails any assemble if a dependency's manifest pulls it into the merged manifest.
abstract class CheckNoInternet : DefaultTask() {
    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun check() {
        val manifest = mergedManifest.get().asFile
        if (manifest.readText().contains("android.permission.INTERNET")) {
            throw GradleException("INTERNET permission found in the merged manifest: $manifest")
        }
    }
}

androidComponents.onVariants { variant ->
    val check = tasks.register<CheckNoInternet>("checkNoInternet${variant.name.replaceFirstChar { it.uppercase() }}") {
        group = "verification"
        description = "Fails if the merged ${variant.name} manifest contains the INTERNET permission."
        mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
    }
    tasks.matching { it.name == "assemble${variant.name.replaceFirstChar { c -> c.uppercase() }}" }
        .configureEach { dependsOn(check) }
}

dependencies {
    implementation(platform(libs.compose.bom))

    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotest.property)
    // Screenshot tests (src/test/.../shots). Test-only: none of this reaches the APK.
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// Screenshots: the ScreenshotsTest class only draws PNGs when -PrecordScreens is given, so the normal gate
// still runs it (it proves every screen composes) but writes nothing. Record with:
//   ./gradlew testDebugUnitTest --tests '*ScreenshotsTest' -PrecordScreens
// Robolectric would otherwise download its Android jars into ~/.m2, outside the dev folder, so the test JVM
// gets its own user.home inside it (first run needs the network once).
tasks.withType<Test>().configureEach {
    val dev = System.getenv("ASSISTANT_DEV") ?: rootDir.parent
    systemProperty("user.home", "$dev/tools/robolectric-home")
    if (project.hasProperty("recordScreens")) {
        systemProperty("roborazzi.test.record", "true")
        systemProperty("screens.dir", "$dev/design/screens/tasks")
        outputs.upToDateWhen { false }
    }
}
