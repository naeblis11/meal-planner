package com.naeblis11.mealplanner.update

/** What Settings says about the update check. A check's failure is said quietly; an install's is flagged. */
object UpdateMessages {
    const val NO_KEY = "This copy of Meal Planner was built without the release key, so it doesn't check for updates."
    const val CHECK_FAILED = "Couldn't reach GitHub to check for updates. Nothing has changed; Meal Planner will look again later."
    const val NO_LIST = "GitHub has no update list for Meal Planner yet. Nothing has changed; Meal Planner will look again later."
    const val FOLDER_REDIRECTED = "Meal Planner's updates folder is a link to somewhere else, so it doesn't check for or download updates."
    const val NOT_VERIFIED = "The update list on GitHub isn't signed with Meal Planner's release key, so it was ignored."
    const val UNREADABLE = "This version can't read the update list on GitHub, so it was ignored."
    const val DOWNLOAD_FAILED = "The download didn't finish, so nothing was installed. Press Install to try again."
    const val SIZE_MISMATCH = "The download wasn't the size the signed update list gives, so it was deleted and nothing was installed."
    const val HASH_MISMATCH = "The download didn't match the signed update list, so it was deleted and nothing was installed."
    const val INSTALL_FAILED = "The installer didn't start, so nothing was installed."
}
