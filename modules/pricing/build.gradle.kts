plugins {
    id("bmp.spring-module")
}

description = "Price lists, prices, price rules, taxes and price resolution."

dependencies {
    implementation(project(":modules:tenant"))
    implementation(project(":modules:catalog"))
}
