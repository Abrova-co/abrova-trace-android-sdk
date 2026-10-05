package ir.abrova.trace.example

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ir.abrova.trace.sdk.AbrovaTrace

class SecondActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(android.widget.TextView(this).apply {
            text = "Second Activity\n\nNavigation breadcrumb was automatically added.\n\nPress back to return."
            textSize = 18f
            setPadding(48, 48, 48, 48)
        })

        AbrovaTrace.addUserBreadcrumb("SecondActivity opened")
    }

    override fun onDestroy() {
        AbrovaTrace.addUserBreadcrumb("SecondActivity closed")
        super.onDestroy()
    }
}
