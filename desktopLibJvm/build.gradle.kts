import com.android.build.gradle.internal.tasks.factory.dependsOn

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    explicitApi()

    jvm {
    }

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(libs.java.jna)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.dbus.java.core)
                implementation(libs.dbus.java.transport)
            }

            resources.srcDir(rootDir.resolve("desktopLibNative/build/bin/universal"))
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

afterEvaluate {
    val resourcesTask = tasks.named("jvmProcessResources")
    resourcesTask.dependsOn(":desktopLibNative:${Tasks.compileNativeUniversal}")
}
