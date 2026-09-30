"""Where the app keeps per-user files that must not live in the install folder.

An installed copy may sit somewhere read-only (Program Files), be shared by
several Windows accounts, or be replaced wholesale on upgrade -- so nothing
the user creates is written next to app.py:

  * secrets (`.env`)      Windows: %LOCALAPPDATA%\\Meal Planner
                          Linux/Pi: ~/.config/meal-planner
  * recipes, database,    Windows: Documents\\Meal Planner
    photos, staging       Linux/Pi: ~/meal-planner  (visible, like Documents --
                          the YAML is meant to be hand-edited and backed up)

`MEAL_PLANNER_HOME` / `MEAL_PLANNER_DATA_DIR` override each location, which
is also how tests, portable installs, and development from a git checkout
point them elsewhere. An install made before the rename keeps using its old
"MAC Meal Planner" / "mac-meal-planner" folders when only those exist.
"""
import ctypes
import os
import sys
from pathlib import Path

APP_NAME = "Meal Planner"
APP_SLUG = "meal-planner"
# Folder names before the rename. An install that already has them keeps
# using them, so upgrading never strands anyone's recipes or password.
LEGACY_APP_NAME = "MAC Meal Planner"
LEGACY_APP_SLUG = "mac-meal-planner"
HOME_ENV = "MEAL_PLANNER_HOME"
DATA_DIR_ENV = "MEAL_PLANNER_DATA_DIR"

# Files and folders inside data_dir().
RECIPES_SUBDIR = "recipes"
IMAGES_SUBDIR = "recipe-images"
DB_FILENAME = "mealplanner.db"


def _prefer_existing(new: Path, legacy: Path) -> Path:
    """The new folder, unless only the pre-rename one exists."""
    if not new.exists() and legacy.exists():
        return legacy
    return new


def config_dir() -> Path:
    override = os.environ.get(HOME_ENV)
    if override:
        return Path(override).expanduser()
    if sys.platform == "win32":
        base = Path(os.environ.get("LOCALAPPDATA") or str(Path.home() / "AppData" / "Local"))
        return _prefer_existing(base / APP_NAME, base / LEGACY_APP_NAME)
    base = Path(os.environ.get("XDG_CONFIG_HOME") or str(Path.home() / ".config"))
    return _prefer_existing(base / APP_SLUG, base / LEGACY_APP_SLUG)


def env_path() -> Path:
    return config_dir() / ".env"


class _GUID(ctypes.Structure):
    _fields_ = [
        ("Data1", ctypes.c_uint32),
        ("Data2", ctypes.c_uint16),
        ("Data3", ctypes.c_uint16),
        ("Data4", ctypes.c_ubyte * 8),
    ]


# FOLDERID_Documents = {FDD39AD0-238F-46AF-ADB4-6C85480369C7}
_FOLDERID_DOCUMENTS = _GUID(
    0xFDD39AD0, 0x238F, 0x46AF,
    (ctypes.c_ubyte * 8)(0xAD, 0xB4, 0x6C, 0x85, 0x48, 0x03, 0x69, 0xC7),
)


# FOLDERID_Desktop = {B4BFCC3A-DB2C-424C-B029-7FE99A87C641}
_FOLDERID_DESKTOP = _GUID(
    0xB4BFCC3A, 0xDB2C, 0x424C,
    (ctypes.c_ubyte * 8)(0xB0, 0x29, 0x7F, 0xE9, 0x9A, 0x87, 0xC6, 0x41),
)


def _windows_known_folder(folder_id: _GUID) -> Path | None:
    """A Windows known folder via the shell API, or None when it can't be asked
    (not Windows, or the call failed)."""
    try:
        shell32 = ctypes.windll.shell32
        ole32 = ctypes.windll.ole32
        buf = ctypes.c_wchar_p()
        if shell32.SHGetKnownFolderPath(ctypes.byref(folder_id), 0, None, ctypes.byref(buf)) == 0:
            try:
                return Path(buf.value)
            finally:
                ole32.CoTaskMemFree(buf)
    except (AttributeError, OSError, ValueError):
        pass
    return None


def _windows_documents_dir() -> Path:
    """The user's real Documents folder via the shell's known-folder API, so a
    Documents folder redirected into OneDrive (or anywhere else) is honoured
    instead of assuming <home>/Documents."""
    return _windows_known_folder(_FOLDERID_DOCUMENTS) or Path.home() / "Documents"


def desktop_dir() -> Path:
    """The user's real Desktop (it lives in OneDrive on many PCs)."""
    if sys.platform == "win32":
        found = _windows_known_folder(_FOLDERID_DESKTOP)
        if found:
            return found
    return Path.home() / "Desktop"


def data_dir() -> Path:
    override = os.environ.get(DATA_DIR_ENV)
    if override:
        return Path(override).expanduser()
    if sys.platform == "win32":
        docs = _windows_documents_dir()
        return _prefer_existing(docs / APP_NAME, docs / LEGACY_APP_NAME)
    # Not XDG_DATA_HOME: that is a hidden folder, and this one holds recipe
    # files the user edits and backs up. Same reasoning as Documents on Windows.
    return _prefer_existing(Path.home() / APP_SLUG, Path.home() / LEGACY_APP_SLUG)


def recipes_dir() -> Path:
    return data_dir() / RECIPES_SUBDIR


def images_dir() -> Path:
    return data_dir() / IMAGES_SUBDIR


def db_path() -> Path:
    return data_dir() / DB_FILENAME
