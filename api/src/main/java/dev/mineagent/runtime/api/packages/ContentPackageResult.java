package dev.mineagent.runtime.api.packages;

public record ContentPackageResult(boolean accepted, String errorCode, ContentPackage contentPackage) {
    public ContentPackageResult {
        errorCode = errorCode == null ? "" : errorCode;
    }

    public static ContentPackageResult accepted(ContentPackage contentPackage) {
        return new ContentPackageResult(true, "", contentPackage);
    }

    public static ContentPackageResult rejected(ContentPackage contentPackage, String code) {
        return new ContentPackageResult(false, code, contentPackage);
    }
}
