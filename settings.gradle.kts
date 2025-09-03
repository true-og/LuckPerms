rootProject.name = "LuckPerms-OG"

include(":api", ":common", ":common:loader-utils", ":bukkit", ":bukkit:loader")

project(":common:loader-utils").projectDir = file("common/loader-utils")

project(":bukkit:loader").projectDir = file("bukkit/loader")
