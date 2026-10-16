package mg.smsgateway.util

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** Masque un numéro pour les logs : +2613***67 */
fun maskPhone(number: String): String =
    if (number.length > 6) number.take(5) + "***" + number.takeLast(2) else "***"
