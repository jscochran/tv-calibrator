package tvcal.android.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowOutward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The SF Symbols the iPhone app uses, mapped once to the closest Material Symbols
 * (material-icons-extended), so every screen picks the same icon. Named after the SF Symbol.
 */
object Symbols {
    val tv: ImageVector = Icons.Outlined.Tv
    /** sparkles.tv (the demo TV): no Material equivalent; the sparkles mark. */
    val sparklesTv: ImageVector = Icons.Outlined.AutoAwesome
    val sparkles: ImageVector = Icons.Outlined.AutoAwesome
    val chevronRight: ImageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight
    val chevronDown: ImageVector = Icons.Outlined.ExpandMore
    val chevronUp: ImageVector = Icons.Outlined.ExpandLess
    /** The navigation bar's back arrow. */
    val back: ImageVector = Icons.AutoMirrored.Outlined.ArrowBack
    val xmark: ImageVector = Icons.Outlined.Close
    val checkmark: ImageVector = Icons.Outlined.Check
    val checkmarkCircle: ImageVector = Icons.Outlined.CheckCircle
    val checkmarkCircleFill: ImageVector = Icons.Filled.CheckCircle
    val checkmarkSquare: ImageVector = Icons.Outlined.CheckBox
    val minus: ImageVector = Icons.Outlined.Remove
    val minusCircle: ImageVector = Icons.Outlined.RemoveCircleOutline
    val lightbulb: ImageVector = Icons.Outlined.Lightbulb
    val lightbulbSlash: ImageVector = Icons.Outlined.FlashlightOff
    val circle: ImageVector = Icons.Outlined.RadioButtonUnchecked
    /** largecircle.fill.circle */
    val circleSelected: ImageVector = Icons.Outlined.RadioButtonChecked
    val playCircle: ImageVector = Icons.Outlined.PlayCircle
    val playFill: ImageVector = Icons.Filled.PlayArrow
    val playRectangle: ImageVector = Icons.Outlined.SmartDisplay
    val playRectangleFill: ImageVector = Icons.Filled.SmartDisplay
    val camera: ImageVector = Icons.Outlined.PhotoCamera
    val cameraAperture: ImageVector = Icons.Outlined.Camera
    val cameraViewfinder: ImageVector = Icons.Outlined.CenterFocusWeak
    val viewfinder: ImageVector = Icons.Outlined.CenterFocusWeak
    val videoSlash: ImageVector = Icons.Outlined.VideocamOff
    val moon: ImageVector = Icons.Outlined.DarkMode
    val timer: ImageVector = Icons.Outlined.Timer
    val clock: ImageVector = Icons.Outlined.Schedule
    val exclamationTriangle: ImageVector = Icons.Outlined.WarningAmber
    /** arrow.triangle.2.circlepath */
    val refresh: ImageVector = Icons.Outlined.Sync
    /** slider.horizontal.3, switch.2, slider.vertical.3 */
    val sliders: ImageVector = Icons.Outlined.Tune
    val flask: ImageVector = Icons.Outlined.Science
    val pencil: ImageVector = Icons.Outlined.Edit
    val trash: ImageVector = Icons.Outlined.Delete
    /** ellipsis.circle (the screen menu). */
    val ellipsis: ImageVector = Icons.Outlined.MoreHoriz
    /** square.and.arrow.up */
    val share: ImageVector = Icons.Outlined.Share
    /** square.and.arrow.down, arrow.down */
    val download: ImageVector = Icons.Outlined.Download
    val arrowDownCircle: ImageVector = Icons.Outlined.DownloadForOffline
    val arrowDown: ImageVector = Icons.Outlined.ArrowDownward
    val arrowRight: ImageVector = Icons.AutoMirrored.Outlined.ArrowForward
    val arrowUpRight: ImageVector = Icons.Outlined.ArrowOutward
    /** arrow.up.right.square */
    val openExternal: ImageVector = Icons.AutoMirrored.Outlined.OpenInNew
    /** externaldrive (a USB stick) */
    val externalDrive: ImageVector = Icons.Outlined.Usb
    val info: ImageVector = Icons.Outlined.Info
    val questionCircle: ImageVector = Icons.AutoMirrored.Outlined.HelpOutline
    val bell: ImageVector = Icons.Outlined.Notifications
    val bellSlash: ImageVector = Icons.Outlined.NotificationsOff
    val bubble: ImageVector = Icons.Outlined.ChatBubbleOutline
    val envelopeFill: ImageVector = Icons.Filled.Email
    /** iphone: the user's phone. */
    val phone: ImageVector = Icons.Outlined.PhoneAndroid
    /** rectangle.stack */
    val stack: ImageVector = Icons.Outlined.Layers
    /** chart.xyaxis.line */
    val chart: ImageVector = Icons.AutoMirrored.Outlined.ShowChart
    /** list.number */
    val listNumber: ImageVector = Icons.Outlined.FormatListNumbered
}
