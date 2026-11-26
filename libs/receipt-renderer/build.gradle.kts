plugins {
    id("bmp.java-conventions")
}

description = "Renders receipts to ESC/POS (58 and 80 mm), PDF and plain text; pure Java, no Spring (DOC-01..DOC-04)."

dependencies {
    implementation(libs.pdfbox)
}
