plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

gradlePlugin {
    plugins {
        register("hiltechBase") {
            id = "com.hiltech.base"
            implementationClass = "com.hiltech.buildlogic.HiltechBasePlugin"
        }
    }
}
