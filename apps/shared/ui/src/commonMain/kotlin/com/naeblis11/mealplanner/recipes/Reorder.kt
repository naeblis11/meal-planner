package com.naeblis11.mealplanner.recipes

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.naeblis11.mealplanner.domain.EditorMoves
import com.naeblis11.mealplanner.ui.theme.MealColors
import kotlin.coroutines.cancellation.CancellationException

/**
 * One list's drag to reorder (P5-R9). A row is dragged by its handle; it follows the pointer above the others, and on
 * release it lands where its middle is (EditorMoves.dropIndex), as the server's editors drop it. Every row's middle is
 * noted as it is laid out; the dragged row's is not updated while it moves. The pointer is followed in the list's own
 * coordinates, which stay still while the row (and its handle) moves. A drag let go outside the list, or cancelled
 * (Escape, the window losing focus), leaves the order as it was.
 */
class ReorderState {
    var dragging: String? by mutableStateOf(null)
        private set
    var offset: Float by mutableFloatStateOf(0f)
        private set
    private val middles = HashMap<String, Float>()
    private val handles = HashMap<String, LayoutCoordinates>()
    private val focus = HashMap<String, FocusRequester>()
    private var list: LayoutCoordinates? = null

    // Where the pointer is, in the list's coordinates, while a row is dragged.
    private var pointer: Offset? = null

    // Gives the focus back to what held it before the drag, once the drag is over.
    private var refocus: (() -> Unit)? = null

    internal fun measured(key: String, coordinates: LayoutCoordinates) {
        if (key != dragging) middles[key] = coordinates.positionInParent().y + coordinates.size.height / 2f
    }

    internal fun listMeasured(coordinates: LayoutCoordinates) {
        list = coordinates
    }

    internal fun handleMeasured(key: String, coordinates: LayoutCoordinates, requester: FocusRequester) {
        handles[key] = coordinates
        focus[key] = requester
    }

    internal fun forget(key: String) {
        handles.remove(key)
        focus.remove(key)
        middles.remove(key)
    }

    /** The row whose handle is under [position], in the list's coordinates; null over anything else. */
    internal fun handleAt(position: Offset): String? {
        val parent = list?.takeIf { it.isAttached } ?: return null
        return handles.entries.firstOrNull { (_, handle) ->
            handle.isAttached && parent.localBoundingBoxOf(handle).contains(position)
        }?.key
    }

    /**
     * Starts dragging [key]'s row, the button having gone down at [at] in the list. Its handle takes the focus, for
     * Escape; [refocus] runs when the drag is over, to give the focus back.
     */
    fun start(key: String, at: Offset, refocus: () -> Unit = {}) {
        dragging = key
        offset = 0f
        pointer = at
        this.refocus = refocus
        focus[key]?.requestFocus()
    }

    /** The pointer is at [at] in the list: the row moves as far as the pointer did. */
    internal fun pointerAt(at: Offset) {
        val last = pointer ?: return
        if (dragging == null) return
        pointer = at
        drag(at.y - last.y)
    }

    internal fun drag(by: Float) {
        if (dragging != null) offset += by
    }

    /**
     * Ends the drag; [onMoveTo] gets the row's key and its new index when that is somewhere else. [keys] are the rows
     * in order, [size] the list's. Let go outside the list, nothing moves.
     */
    fun end(keys: List<String>, size: IntSize, onMoveTo: (String, Int) -> Unit) {
        val key = dragging ?: return
        val moved = offset
        val at = pointer
        cancel()
        if (at == null || at.x < 0f || at.y < 0f || at.x > size.width || at.y > size.height) return
        val from = keys.indexOf(key)
        if (from < 0) return
        val to = EditorMoves.dropIndex(keys.map { middles[it] ?: 0f }, from, moved)
        if (to != from) onMoveTo(key, to)
    }

    fun cancel() {
        val back = refocus
        dragging = null
        offset = 0f
        pointer = null
        refocus = null
        back?.invoke()
    }
}

/**
 * The list whose rows drag: a Column that follows a drag begun on one of its [DragHandle]s. With [onMoveTo] null it
 * is a plain Column (the phone, or nowhere to drop).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ReorderColumn(
    state: ReorderState,
    keys: List<String>,
    onMoveTo: ((String, Int) -> Unit)?,
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onMoveTo == null) {
        Column(modifier, verticalArrangement = verticalArrangement, content = content)
        return
    }
    val currentKeys by rememberUpdatedState(keys)
    val currentMove by rememberUpdatedState(onMoveTo)
    // The window losing focus (Alt+Tab mid-drag) cancels, as the release may never arrive.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) { if (!windowFocused) state.cancel() }
    // The list's fields form a group, so the one that had the focus before a drag can have it back after.
    val listFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    Column(
        modifier
            .onGloballyPositioned { state.listMeasured(it) }
            .focusRequester(listFocus)
            .focusGroup()
            .pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // The primary button only: a right press opens menus, it doesn't move rows.
                    if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                    val key = state.handleAt(down.position) ?: return@awaitEachGesture
                    down.consume()
                    val moving = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
                    // Focus outside the list can't be named from here (Compose has no "what is focused" to save), so
                    // it is let go rather than left on a handle that can no longer hold it.
                    val saved = listFocus.saveFocusedChild()
                    state.start(key, down.position) {
                        if (!saved || !listFocus.restoreFocusedChild()) focusManager.clearFocus()
                    }
                    state.pointerAt(moving.position)
                    val released = try {
                        drag(moving.id) { change ->
                            change.consume()
                            state.pointerAt(change.position)
                        }
                    } catch (e: CancellationException) {
                        state.cancel()
                        throw e
                    }
                    if (released) state.end(currentKeys, size, currentMove) else state.cancel()
                }
            },
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/** A row of a reorderable list: its place is noted for the drop, and while dragged it follows the pointer, above the rest. */
fun Modifier.reorderable(state: ReorderState, key: String): Modifier =
    onGloballyPositioned { state.measured(key, it) }
        .then(if (state.dragging == key) Modifier.zIndex(1f).graphicsLayer { translationY = state.offset } else Modifier)

/**
 * The handle a row is dragged by (the server's grip), described "Drag <label>"; the [ReorderColumn] around the rows
 * follows the drag. It takes the focus while it drags, so Escape can cancel; it is not a stop for Tab otherwise, as Up
 * and Down are the keyboard's way to move a row.
 */
@Composable
fun DragHandle(state: ReorderState, key: String, label: String) {
    val requester = remember { FocusRequester() }
    DisposableEffect(state, key) { onDispose { state.forget(key) } }
    Box(
        Modifier
            .size(40.dp)
            .semantics { contentDescription = "Drag $label" }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && state.dragging == key) {
                    state.cancel()
                    true
                } else {
                    false
                }
            }
            .focusRequester(requester)
            .focusProperties { canFocus = state.dragging == key }
            .focusable()
            .onGloballyPositioned { state.handleMeasured(key, it, requester) },
        contentAlignment = Alignment.Center,
    ) { Text("\u2261", color = MealColors.Muted, fontSize = 22.sp) }
}
