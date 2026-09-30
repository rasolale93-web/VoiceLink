package com.voicelink
import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import okhttp3.*
import java.util.concurrent.TimeUnit
class MainActivity:Activity(){
 var ws:WebSocket?=null; lateinit var status:TextView; lateinit var url:EditText; lateinit var code:EditText; lateinit var role:Spinner; lateinit var call:Button
 override fun onCreate(b:Bundle?){super.onCreate(b);ui()}
 fun ui(){
  val r=LinearLayout(this);r.orientation=LinearLayout.VERTICAL;r.setPadding(28,40,28,28)
  val t=TextView(this);t.text="VoiceLink";t.textSize=30f;t.gravity=Gravity.CENTER;t.setTextColor(Color.rgb(30,110,80));r.addView(t)
  url=EditText(this);url.hint="آدرس WebSocket مثل ws://SERVER:8080";r.addView(url)
  code=EditText(this);code.hint="کد اتصال مثل 1234";r.addView(code)
  role=Spinner(this);role.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,arrayOf("تماس‌گیرنده","گیرنده"));r.addView(role)
  val c=Button(this);c.text="اتصال به سرور";r.addView(c)
  status=TextView(this);status.text="⚪ متصل نیست";status.textSize=18f;r.addView(status)
  call=Button(this);call.text="شروع تماس آزمایشی";call.isEnabled=false;r.addView(call)
  val d=Button(this);d.text="قطع اتصال";r.addView(d);setContentView(r)
  c.setOnClickListener{connect()};d.setOnClickListener{ws?.close(1000,"user")};call.setOnClickListener{ws?.send("""{"type":"call","code":"${code.text}"}""");status.text="📞 پیام تماس ارسال شد"}
 }
 fun connect(){
  val u=url.text.toString().trim();if(u.isEmpty()){status.text="آدرس سرور را وارد کنید";return};status.text="در حال اتصال..."
  val client=OkHttpClient.Builder().readTimeout(0,TimeUnit.MILLISECONDS).build()
  ws=client.newWebSocket(Request.Builder().url(u).build(),object:WebSocketListener(){
   override fun onOpen(w:WebSocket,x:Response){runOnUiThread{status.text="🟢 اتصال واقعی برقرار است";call.isEnabled=true};w.send("""{"type":"join","code":"${code.text}","role":"${role.selectedItem}"}""")}
   override fun onMessage(w:WebSocket,s:String){runOnUiThread{status.text="📩 $s"}}
   override fun onFailure(w:WebSocket,e:Throwable,x:Response?){runOnUiThread{status.text="🔴 ${e.message}";call.isEnabled=false}}
   override fun onClosed(w:WebSocket,c:Int,s:String){runOnUiThread{status.text="⚪ اتصال بسته شد";call.isEnabled=false}}
  })
 }
}