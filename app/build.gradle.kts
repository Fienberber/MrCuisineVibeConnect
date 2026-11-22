import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.toque"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.toque"
        minSdk = 23          // Android 6.0, celui du Monsieur Cuisine Connect
        targetSdk = 28
        // Pour publier une mise à jour : augmenter les deux valeurs puis pousser sur GitHub
        // (la version est publiée automatiquement, voir .github/workflows/release.yml).
        // Branche de test du Monsieur Cuisine Smart : numéro au-dessus de main (0.7.x), pour qu'un Smart
        // ne se voie jamais proposer une version de main sans le Smart. Pré-version seulement.
        versionCode = 12
        versionName = "0.8.0-smart.1"
        // APK de test du Monsieur Cuisine Smart (gradlew -PtoqueSmart=true) : le JIT d'Android 8.1 de sa
        // tablette compile mal certaines expressions Kotlin (portages Flav974 et Camybrith) ; le mode
        // sûr le contourne mais ralentit toute l'appli. Jamais dans l'APK du Connect.
        manifestPlaceholders["vmSafeMode"] = (project.findProperty("toqueSmart") == "true").toString()
    }

    signingConfigs {
        // Clé du projet, publique exprès : toutes les compilations (PC, GitHub) portent la même
        // signature, donc chaque mise à jour s'installe par-dessus la précédente.
        create("toque") {
            storeFile = file("signature/toque.p12")
            storeType = "PKCS12"
            storePassword = "toque-public"
            keyAlias = "toque"
            keyPassword = "toque-public"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("toque")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("toque")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
}

dependencies {
    implementation(project(":core"))
}
