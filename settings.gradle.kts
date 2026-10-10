pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Communicator"

include(":app")
include(":communication:carrier")
include(":communication:core")
include(":communication:diagnostics")
include(":communication:encrypted")
include(":communication:history")
include(":communication:ims")
include(":communication:mesh")
include(":communication:rcs")
include(":communication:recording")
include(":communication:sip")
include(":communication:sms")
include(":communication:spam")
include(":communication:mms")
include(":communication:webrtc")
include(":contacts:core")
include(":contacts:ui")
include(":core:common")
include(":core:extensions")
include(":core:permissions")
include(":data:core")
include(":data:security")
include(":ui:core")
include(":ui:dialer")
include(":ui:incoming")
include(":ui:settings")
include(":ui:sip")
include(":ui:webrtc")
