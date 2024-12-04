package com.seoultech.onde

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class ChatRoomActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private lateinit var auth: FirebaseAuth
    private val client = OkHttpClient()

    private lateinit var topicTextView: TextView
    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var messageEditText: EditText
    private lateinit var sendButton: Button

    private lateinit var chatAdapter: ChatAdapter
    private val messages = mutableListOf<ChatMessage>()

    private lateinit var chatId: String
    private lateinit var currentUserId: String
    private lateinit var otherUserId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_room)
        auth = FirebaseAuth.getInstance()

        val userId = auth.currentUser?.uid ?: "unknown"
        currentUserId = HashUtils.generateUserIdHash(userId)


        // 인텐트로부터 상대방 사용자 ID 받기
        otherUserId = intent.getStringExtra("userIdHash") ?: ""

        if (currentUserId.isEmpty() || otherUserId.isEmpty()) {
            finish()
            return
        }

        topicTextView = findViewById(R.id.topicTextView)
        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        messageEditText = findViewById(R.id.messageEditText)
        sendButton = findViewById(R.id.sendButton)

        chatAdapter = ChatAdapter(messages, currentUserId)
        chatRecyclerView.layoutManager = LinearLayoutManager(this)
        chatRecyclerView.adapter = chatAdapter

        // 채팅방 ID 생성 또는 가져오기
        createOrGetChatRoom()

        // 메시지 전송 버튼 클릭 리스너
        sendButton.setOnClickListener {
            sendMessage()
        }

        // 메시지 입력 감지
        messageEditText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                sendButton.isEnabled = !s.isNullOrEmpty()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { }
        })


        fetchUsersAndGenerateTopic(currentUserId, otherUserId)
    }

    private fun createOrGetChatRoom() {
        // 두 사용자 ID를 정렬하여 고유한 채팅방 ID 생성
        chatId = if (currentUserId < otherUserId) {
            currentUserId + "_" + otherUserId
        } else {
            otherUserId + "_" + currentUserId
        }

        val chatRef = db.collection("chats").document(chatId)

        chatRef.get().addOnSuccessListener { document ->
            if (!document.exists()) {
                val chatData = hashMapOf(
                    "participants" to listOf(currentUserId, otherUserId)
                )
                chatRef.set(chatData)
            }
            // 메시지 수신 대기
            listenForMessages()
        }
    }

    private fun listenForMessages() {
        db.collection("chats").document(chatId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    // 에러 처리
                    return@addSnapshotListener
                }
                if (snapshots != null) {
                    messages.clear()
                    for (doc in snapshots.documents) {
                        val message = doc.toObject(ChatMessage::class.java)
                        if (message != null) {
                            messages.add(message)
                        }
                    }
                    chatAdapter.notifyDataSetChanged()
                    chatRecyclerView.scrollToPosition(messages.size - 1)
                }
            }
    }

    private fun sendMessage() {
        val content = messageEditText.text.toString()
        if (content.isEmpty()) return

        val message = ChatMessage(
            senderId = currentUserId,
            content = content,
            timestamp = System.currentTimeMillis()
        )

        db.collection("chats").document(chatId)
            .collection("messages")
            .add(message)
            .addOnSuccessListener {
                messageEditText.text.clear()
            }
    }

    private fun fetchUsersAndGenerateTopic(user1Id: String, user2Id: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Firestore에서 두 사용자 정보 가져오기
                val user1 = db.collection("users").document(user1Id).get().await()
                val user2 = db.collection("users").document(user2Id).get().await()

                val interests1 = user1.getString("interests") ?: ""
                val interests2 = user2.getString("interests") ?: ""

                // GPT로 주제 생성 요청
                val topic = generateSmallTalkTopic(interests1, interests2)

                withContext(Dispatchers.Main) {
                    // 주제를 UI에 표시
                    topicTextView.text = topic
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    topicTextView.text = "주제를 가져오는 데 실패했습니다."
                }
            }
        }
    }

    private suspend fun generateSmallTalkTopic(interests1: String, interests2: String): String {
        val apiUrl = "https://api.openai.com/v1/chat/completions"
        val apiKey = getString(R.string.openai_api_key)
        val prompt = "사용자 A는 '$interests1'에 관심이 있고, 사용자 B는 '$interests2'에 관심이 있습니다. 두 관심사와 관련된  스몰토크 주제를 한 줄로 짧게 제안해주세요. 창의적이지 않아도 됩니다. 완성된 자연스러운 문장으로 말해주세요"

        val jsonBody = JSONObject().apply {
            put("model", "gpt-3.5-turbo")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("max_tokens", 50)
            put("temperature", 0.5)
        }

        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val jsonResponse = JSONObject(responseBody ?: "")
                jsonResponse.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
            } else {
                "GPT 응답 실패"
            }
        } catch (e: Exception) {
            "네트워크 오류: ${e.message}"
        }
    }
}

