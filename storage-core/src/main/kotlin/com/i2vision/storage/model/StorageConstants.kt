package com.i2vision.storage.model

/**
 * Public storage constants.
 * These are safe to expose as they don't reveal the full path structure.
 */
object StorageConstants {
    /**
     * The semantic cache directory name.
     * Safe to expose - this is just a directory name, not the full path structure.
     */
    const val SEMANTIC_CACHE_DIR = ".semantic-cache"
    
    /**
     * The vision-ai control plane directory name.
     * Safe to expose - this is just a directory name, not the full path structure.
     */
    const val VISION_AI_DIR = ".vision-ai"
}
