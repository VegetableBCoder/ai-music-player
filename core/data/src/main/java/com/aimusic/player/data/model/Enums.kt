package com.aimusic.player.data.model

enum class AnalysisStatus { UNANALYZED, ANALYZING, LINKED, FAILED }

enum class PlayMode { LIST_LOOP, SINGLE_LOOP, SHUFFLE }

enum class SourceKind { MUSIC, LYRICS }

enum class LyricSource { SAME_DIR, EXTERNAL_DIR, TAG_MATCH }

enum class RunStatus { RUNNING, COMPLETED, ABORTED }
