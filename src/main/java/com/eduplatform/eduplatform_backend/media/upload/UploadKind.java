package com.eduplatform.eduplatform_backend.media.upload;

/** Families of accepted binary. Each has its own size ceiling and its own serving rules. */
public enum UploadKind {

    IMAGE(true),
    VIDEO(true),
    /**
     * PDFs and anything else document-shaped. Never served inline: the admin portal proxies
     * {@code /api/} same-origin, so an inline document rendered from this API would run in the
     * portal's origin and could read its tokens.
     */
    DOCUMENT(false);

    private final boolean inlineSafe;

    UploadKind(boolean inlineSafe) {
        this.inlineSafe = inlineSafe;
    }

    /** Whether a browser may render this in place instead of being forced to download it. */
    public boolean inlineSafe() {
        return inlineSafe;
    }
}
