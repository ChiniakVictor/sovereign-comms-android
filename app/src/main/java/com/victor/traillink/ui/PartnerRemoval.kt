package com.victor.traillink.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victor.traillink.core.Geo
import com.victor.traillink.data.RemovedPartners

/** What to remove: a node number plus (for chat-only entries) the chat key. */
data class RemoveTarget(val num: Long, val name: String, val key: String? = null)

/** Confirm dialog used by Partners (long-press / Remove button), the chat list (long-press) and a chat's ⋮ menu. */
@Composable
fun RemovePartnerDialog(vm: AppViewModel, t: RemoveTarget, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var block by remember { mutableStateOf(false) }
    var delChat by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDone,
        title = { Text("Remove ${t.name}?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("This removes from THIS phone only:", fontSize = 15.sp)
                Text("• ${t.name} from your partners list and map\n• their trail and last location\n• map pins they shared" +
                    if (delChat) "\n• your direct chat with them (messages and voice clips)" else "", fontSize = 15.sp, modifier = Modifier.padding(vertical = 6.dp))
                Text(if (delChat) "Your direct chat with them will be deleted for good." else "Your direct chat with them is kept. If they show up again, it's still there.",
                    fontSize = 14.sp, color = if (delChat) Color(0xFFFF8A80) else Color(0xFF90A4AE))
                Text("Nothing is sent to them or anyone else. Their messages in the group chat stay.", fontSize = 14.sp, color = Color(0xFF90A4AE))
                Text(if (block) "Blocked: they stay hidden, even if they share a position, message, pin or call you again. Unblock any time in More → Partners."
                     else "Old saved data won't bring them back. If they're active again (new position, message, pin or call), they show up again on their own.",
                    fontSize = 14.sp, color = Color(0xFF90A4AE), modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.fillMaxWidth().clickable { delChat = !delChat }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(delChat, { delChat = it }); Text("Also delete our chat", fontSize = 15.sp)
                }
                Row(Modifier.fillMaxWidth().clickable { block = !block }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(block, { block = it }); Text("Also block (keep hidden)", fontSize = 15.sp)
                }
            }
        },
        confirmButton = { TextButton({
            vm.removePartner(t.num, t.name, block, t.key, delChat)
            android.widget.Toast.makeText(ctx, "${t.name} removed from this phone", android.widget.Toast.LENGTH_SHORT).show()
            onDone()
        }) { Text(if (block) "Remove & block" else "Remove", color = Color(0xFFFF8A80)) } },
        dismissButton = { TextButton(onDone) { Text("Cancel") } })
}

/** More → Partners: bulk cleanup + blocked list. */
@Composable
fun PartnerCleanupSection(vm: AppViewModel) {
    val ctx = LocalContext.current
    val partners by vm.partners.collectAsState()
    val removed by RemovedPartners.list.collectAsState()
    var days by remember { mutableIntStateOf(30) }
    var preview by remember { mutableStateOf<List<com.victor.traillink.data.NodeEntity>?>(null) }
    Text("Partners", style = MaterialTheme.typography.titleLarge)
    Text("Clean out old partners. This only changes this phone; anyone still active shows up again when they're heard.", fontSize = 14.sp, color = Color(0xFF90A4AE))
    Text("Remove partners not heard in:", fontSize = 16.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(7, 30, 90).forEach { d -> FilterChip(days == d, { days = d }, { Text("$d days") }) }
    }
    val count = remember(partners, days) { vm.stalePartners(days).size }
    BigButton(if (count == 0) "No partners older than $days days" else "Review $count partner${if (count == 1) "" else "s"} to remove…", Modifier.fillMaxWidth(), primary = false) {
        if (count > 0) preview = vm.stalePartners(days)
    }
    val blocked = removed.values.filter { it.blocked }.sortedBy { it.name }
    if (blocked.isNotEmpty()) {
        Text("Blocked (${blocked.size})", fontWeight = FontWeight.Bold)
        blocked.forEach { e ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, fontSize = 16.sp, modifier = Modifier.weight(1f))
                TextButton({ RemovedPartners.unblock(e.num) }) { Text("Unblock") }
            }
        }
        Text("Unblocked partners show up again the next time they're heard.", fontSize = 13.sp, color = Color(0xFF90A4AE))
    }
    preview?.let { list ->
        var keep by remember(list) { mutableStateOf(setOf<Long>()) }
        var delChats by remember(list) { mutableStateOf(false) }
        val now = System.currentTimeMillis()
        AlertDialog(onDismissRequest = { preview = null },
            title = { Text("Remove ${list.size - keep.size} old partner${if (list.size - keep.size == 1) "" else "s"}?") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("Not heard in $days days. Untick anyone to keep. Removes them, their trail and their pins from this phone only." +
                    if (delChats) " Your direct chats with them are deleted too." else " Your direct chats with them are kept.", fontSize = 14.sp)
                Row(Modifier.fillMaxWidth().clickable { delChats = !delChats }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(delChats, { delChats = it }); Text("Also delete our chats", fontSize = 15.sp)
                }
                HorizontalDivider()
                list.forEach { n ->
                    Row(Modifier.fillMaxWidth().clickable { keep = if (n.num in keep) keep - n.num else keep + n.num }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(n.num !in keep, { keep = if (it) keep - n.num else keep + n.num })
                        Column {
                            Text(n.longName, fontSize = 16.sp)
                            Text(if (n.lastHeard == 0L) "never heard" else "heard ${Geo.formatAge(now - n.lastHeard)}" +
                                if (n.num > 0xFFFFFFFFL) " · internet" else " · radio", fontSize = 13.sp, color = Color(0xFF90A4AE))
                        }
                    }
                }
            } },
            confirmButton = { TextButton({
                val go = list.filter { it.num !in keep }
                vm.removeMany(go, delChats); preview = null
                android.widget.Toast.makeText(ctx, "Removed ${go.size} partner${if (go.size == 1) "" else "s"}", android.widget.Toast.LENGTH_SHORT).show()
            }, enabled = list.size > keep.size) { Text("Remove", color = Color(0xFFFF8A80)) } },
            dismissButton = { TextButton({ preview = null }) { Text("Cancel") } })
    }
}
