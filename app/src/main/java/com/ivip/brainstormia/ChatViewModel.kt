package com.ivip.brainstormia

import com.ivip.brainstormia.data.models.AIModel
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.brainstormia.ConversationType
import com.ivip.brainstormia.data.db.AppDatabase
import com.ivip.brainstormia.data.db.ChatDao
import com.ivip.brainstormia.data.db.ChatMessageEntity
import com.ivip.brainstormia.data.db.ConversationInfo
import com.ivip.brainstormia.data.db.ConversationMetadataDao
import com.ivip.brainstormia.data.db.ConversationMetadataEntity
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.ivip.brainstormia.data.db.ModelPreferenceDao
import com.ivip.brainstormia.data.db.ModelPreferenceEntity
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull

enum class LoadingState { IDLE, LOADING, ERROR }

const val NEW_CONVERSATION_ID = -1L
private const val MAX_HISTORY_MESSAGES = 20

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    // Cliente OpenAI
    private val openAIClient = OpenAIClient(BuildConfig.OPENAI_API_KEY)

    private val auth = FirebaseAuth.getInstance()
    private val appDb = AppDatabase.getDatabase(application)
    private val chatDao: ChatDao = appDb.chatDao()
    private val metadataDao: ConversationMetadataDao = appDb.conversationMetadataDao()
    private val modelPreferenceDao: ModelPreferenceDao = appDb.modelPreferenceDao()

    // Lista de modelos disponíveis (OpenAI)
    private val availableModels = listOf(AIModel(id = "gpt-4.1", displayName = "GPT-4.1", apiEndpoint = "gpt-4.1"),
       AIModel(id = "gpt-4.1-mini", displayName = "GPT-4.1 Mini", apiEndpoint = "gpt-4.1-mini"),
       AIModel(id = "gpt-4.1-nano", displayName = "GPT-4.1 Nano", apiEndpoint = "gpt-4.1-nano"),
        AIModel(id = "gpt-4o", displayName = "GPT-4o", apiEndpoint = "gpt-4o"),
       AIModel(id = "gpt-4.5-preview", displayName = "GPT-4.5 Preview", apiEndpoint = "gpt-4.5-preview"),
        AIModel(id = "o1", displayName = "GPT o1", apiEndpoint = "o1"),
        //AIModel(id = "o1-pro", displayName = "GPT o1 Pro", apiEndpoint = "o1-pro"),
        AIModel(id = "o3", displayName = "GPT o3", apiEndpoint = "o3"),
        AIModel(id = "o3-mini", displayName = "GPT o3 Mini", apiEndpoint = "o3-mini"),
        AIModel(id = "o4-mini", displayName = "GPT o4 Mini", apiEndpoint = "o4-mini"),
        AIModel(id = "gpt-4-turbo", displayName = "GPT-4o Turbo", apiEndpoint = "gpt-4-turbo"),
        AIModel(id = "gpt-3.5-turbo", displayName = "GPT-3.5 Turbo", apiEndpoint = "gpt-3.5-turbo")
    )

    // Definindo o modelo padrão como GPT-4o
    private val _selectedModel = MutableStateFlow(availableModels[3]) // GPT-4o como padrão
    val selectedModel: StateFlow<AIModel> = _selectedModel.asStateFlow()

    // Expor a lista de modelos
    val modelOptions: List<AIModel> = availableModels

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Estados para gerenciar o reconhecimento de voz
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    fun startListening() {
        _isListening.value = true
        // Timeout para parar de ouvir após 30 segundos
        viewModelScope.launch {
            delay(30000)
            stopListening()
        }
    }

    fun stopListening() {
        _isListening.value = false
    }

    // Método para lidar com o resultado do reconhecimento de voz
    fun handleVoiceInput(text: String) {
        stopListening()
        // O texto reconhecido será enviado como uma mensagem normal
        // Você pode processá-lo aqui antes de enviá-lo para o serviço
    }

    // Método para atualizar o modelo selecionado
    fun selectModel(model: AIModel) {
        if (model.id != _selectedModel.value.id) {
            _selectedModel.value = model

            // Salvar no banco de dados
            viewModelScope.launch {
                try {
                    modelPreferenceDao.insertOrUpdatePreference(
                        ModelPreferenceEntity(
                            userId = _userIdFlow.value,
                            selectedModelId = model.id
                        )
                    )
                    Log.i("ChatViewModel", "Saved model preference: ${model.displayName}")
                } catch (e: Exception) {
                    Log.e("ChatViewModel", "Error saving model preference", e)
                    _errorMessage.value = "Erro ao salvar preferência de modelo: ${e.localizedMessage}"
                }
            }
        }
    }

    private val _currentConversationId = MutableStateFlow<Long?>(null)
    val currentConversationId: StateFlow<Long?> = _currentConversationId.asStateFlow()

    private val _loadingState = MutableStateFlow(LoadingState.IDLE)
    val isLoading: StateFlow<Boolean> = _loadingState.map { it == LoadingState.LOADING }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = false
        )

    private val _clearConversationListEvent = MutableStateFlow(false)
    val clearConversationListEvent: StateFlow<Boolean> = _clearConversationListEvent.asStateFlow()

    private val _userIdFlow = MutableStateFlow(getCurrentUserId())

    private val _showConversations = MutableStateFlow(true)
    val showConversations: StateFlow<Boolean> = _showConversations.asStateFlow()

    /* ─── Flag de prontidão ─────────────────────────────────────────────── */

    // 1) flag interna mutável
    private val _isReady = MutableStateFlow(false)

    // 2) flag pública para quem observa do lado de fora
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    init {
        // aguarda a criação da "nova conversa" ou qualquer tarefa
        loadInitialConversationOrStartNew()
        _isReady.value = true          // <- PRONTO ✔
        auth.addAuthStateListener { firebaseAuth ->
            val newUser = firebaseAuth.currentUser
            val newUserId = newUser?.uid ?: "local_user"
            val previousUserId = _userIdFlow.value

            Log.d("ChatViewModel", "Auth state changed: $previousUserId -> $newUserId")

            if (newUserId != previousUserId) {
                viewModelScope.launch {
                    if (newUser != null) {
                        // User logged in
                        Log.d("ChatViewModel", "User logged in: $newUserId")
                        _userIdFlow.value = newUserId
                        _showConversations.value = true

                        // Try to load conversations with delay
                        delay(300)
                        forceLoadConversationsAfterLogin()
                    } else {
                        // User logged out
                        Log.d("ChatViewModel", "User logged out")
                        _userIdFlow.value = "local_user"
                        _showConversations.value = false
                        _currentConversationId.value = NEW_CONVERSATION_ID
                    }
                }
            }
        }
        loadInitialConversationOrStartNew()
        viewModelScope.launch {
            modelPreferenceDao.getModelPreference(_userIdFlow.value)
                .collect { preference ->
                    if (preference != null) {
                        val savedModel = availableModels.find { it.id == preference.selectedModelId }
                        if (savedModel != null) {
                            _selectedModel.value = savedModel
                            Log.i("ChatViewModel", "Loaded user model preference: ${savedModel.displayName}")
                        }
                    }
                }
        }
    }

    private fun getCurrentUserId(): String =
        FirebaseAuth.getInstance().currentUser?.uid ?: "local_user"

    private val rawConversationsFlow: Flow<List<ConversationInfo>> =
        _userIdFlow.flatMapLatest { uid ->
            Log.d("ChatViewModel", "Initializing rawConversationsFlow for user: $uid")
            if (uid.isBlank()) {
                Log.w("ChatViewModel", "Empty user ID in rawConversationsFlow, emitting empty list")
                flowOf(emptyList())
            } else {
                chatDao.getConversationsForUser(uid)
                    .onStart {
                        Log.d("ChatViewModel", "Starting to collect conversations for user: $uid")
                    }
                    .onEmpty {
                        Log.d("ChatViewModel", "No conversations found for user: $uid")
                    }
                    .catch { e ->
                        Log.e("ChatViewModel", "Error loading raw conversations flow for user: $uid", e)
                        _errorMessage.value = "Erro ao carregar lista de conversas (raw)."
                        emit(emptyList())
                    }
            }
        }

    private val metadataFlow: Flow<List<ConversationMetadataEntity>> =
        _userIdFlow.flatMapLatest { uid ->
            metadataDao.getMetadataForUser(uid)
                .catch { e ->
                    Log.e("ChatViewModel", "Error loading metadata flow", e)
                    emit(emptyList())
                }
        }

    val conversationListForDrawer: StateFlow<List<ConversationDisplayItem>> =
        combine(rawConversationsFlow, metadataFlow, _showConversations, _userIdFlow) { conversations, metadataList, showConversations, currentUserId ->
            if (!showConversations || auth.currentUser == null) {
                return@combine emptyList<ConversationDisplayItem>()
            }

            Log.d("ChatViewModel", "Combining ${conversations.size} convs and ${metadataList.size} metadata entries for user $currentUserId.")

            val userMetadata = metadataList.filter { it.userId == currentUserId }
            val metadataMap = userMetadata.associateBy({ it.conversationId }, { it.customTitle })

            conversations.map { convInfo ->
                val customTitle = metadataMap[convInfo.id]?.takeIf { it.isNotBlank() }
                val finalTitle = customTitle ?: generateFallbackTitleSync(convInfo.id)
                val conversationType = determineConversationType(finalTitle, convInfo.id)
                ConversationDisplayItem(
                    id = convInfo.id,
                    displayTitle = finalTitle,
                    lastTimestamp = convInfo.lastTimestamp,
                    conversationType = conversationType
                )
            }
        }
            .flowOn(Dispatchers.Default)
            .catch { e ->
                Log.e("ChatViewModel", "Error combining conversations and metadata", e)
                withContext(Dispatchers.Main.immediate) {
                    _errorMessage.value = "Erro ao processar lista de conversas para exibição."
                }
                emit(emptyList())
            }
            .stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList())

    val messages: StateFlow<List<com.ivip.brainstormia.ChatMessage>> =
        _currentConversationId.flatMapLatest { convId ->
            Log.d("ChatViewModel", "[State] CurrentConversationId changed: $convId")
            when (convId) {
                null, NEW_CONVERSATION_ID -> {
                    flowOf(listOf(com.ivip.brainstormia.ChatMessage(welcomeMessageText, Sender.BOT)))
                }
                else -> chatDao.getMessagesForConversation(convId, _userIdFlow.value)
                    .map { entities ->
                        Log.d("ChatViewModel", "[State] Mapping ${entities.size} entities for conv $convId")
                        mapEntitiesToUiMessages(entities)
                    }
                    .catch { e ->
                        Log.e("ChatViewModel", "Error loading messages for conversation $convId", e)
                        withContext(Dispatchers.Main.immediate) {
                            _errorMessage.value = "Erro ao carregar mensagens da conversa."
                        }
                        emit(emptyList())
                    }
            }
        }
            .stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(5000L), initialValue = emptyList())

    private val welcomeMessageText = "Olá! Eu sou o Brainstormia 👽, seu assistente virtual de criatividade e sabedoria. Estou aqui para inspirar suas ideias, compartilhar conhecimento prático e ajudar você a encontrar soluções inteligentes. Como posso impulsionar sua mente hoje?"

    private val brainstormiaSystemPrompt = """
    ## Persona e Propósito Central
    Você é **Brainstormia**, um companheiro virtual erudito e inspirador, desenvolvido para oferecer um espaço de conhecimento, descoberta e reflexão. Seu propósito é conversar sobre QUALQUER ASSUNTO, respondendo dúvidas, explorando ideias e oferecendo perspectivas embasadas nos pensamentos dos mais renomados intelectuais da história. Você transforma informação em sabedoria aplicável, conectando temas contemporâneos à sabedoria atemporal dos grandes pensadores.
    
    ## Base de Conhecimento e Capacidades
    1. **Filosofia**  
       - Clássica: Sócrates, Platão, Aristóteles, estoicismo, epicurismo  
       - Oriental: Confúcio, Lao Tsé, Buda  
       - Moderna: Kant, Nietzsche, Sartre, Beauvoir
    
    2. **Ciência**  
       Einstein, Darwin, Curie, Hawking
    
    3. **Literatura & Arte**  
       Escritores, poetas e artistas influentes ao longo dos séculos
    
    4. **Psicologia**  
       - Psicanálise: Freud, Jung, Lacan  
       - Humanista: Maslow, Rogers, Frankl  
       - Comportamental/Cognitiva: Skinner, Ellis, Beck, Bandura  
       - Desenvolvimento: Piaget, Vygotsky, Erikson, Kohlberg  
       - Positiva: Seligman, Csikszentmihalyi, Dweck
    
    5. **Sabedoria Prática**  
       Aplicação de conceitos filosóficos, psicológicos e científicos ao cotidiano
    
    6. **Tecnologia & Inovação**  
       Turing, Jobs, Lovelace
    
    7. **Negócios & Liderança**  
       Princípios de grandes empreendedores e líderes
    
    8. **Bem-Estar & Desenvolvimento Pessoal**  
       Filosofias de vida plena e significativa
    
    ## Estilo de Interação e Tom
    - **Inspirador & Esclarecedor**: Estimula curiosidade  
    - **Acessível & Didático**: Explica conceitos complexos com clareza  
    - **Reflexivo & Profundo**: Incentiva o pensamento crítico  
    - **Versátil**: Adapta-se ao nível do usuário  
    - **Encorajador**: Motiva o aprendizado contínuo  
    - **Prático**: Foco na aplicação imediata
    
    ## Limites e Flexibilidade
    1. **Abordagem Universal**: Discuta qualquer tópico, sempre conectando-o aos grandes pensadores;  
    2. **Evite Imposições**: Apresente múltiplas perspectivas sem afirmar verdades absolutas;  
    3. **Citações & Referências**: Inclua menções a autores e obras sempre que possível;  
    4. **Reconheça Limites**: Para temas muito contemporâneos ou técnicos, reconheça quando recorrer a fontes atualizadas.
    
    ## Quem é Você?
    Ao ser perguntado "Quem é você?", responda apenas com a saudação:
    > **Olá! Eu sou o Brainstormia 👽, seu assistente virtual de criatividade e sabedoria.**
    
    ## Objetivo Final
    Ser um companheiro que promove crescimento intelectual, reflexão profunda e aplicação prática da sabedoria humana, ajudando o usuário a transformar conhecimento em poder para seu dia a dia, independentemente do assunto.  
    """
    fun handleLogin() {
        Log.d("ChatViewModel", "handleLogin() called - user=${_userIdFlow.value}")

        // Make sure conversations are visible
        _showConversations.value = true

        // Force reload of conversations with multiple attempts
        viewModelScope.launch {
            // First attempt
            val currentUserId = getCurrentUserId()
            Log.d("ChatViewModel", "handleLogin: reloading conversations for user $currentUserId")

            // Reset the flow to force recomposition
            _userIdFlow.value = ""
            delay(50)
            _userIdFlow.value = currentUserId

            // Check after a short delay if conversations loaded
            delay(500)
            if (conversationListForDrawer.value.isEmpty() && auth.currentUser != null) {
                Log.w("ChatViewModel", "First attempt failed, trying second refresh")

                // Second attempt with longer delay
                refreshConversationList()

                // One final check with longer delay
                delay(1000)
                if (conversationListForDrawer.value.isEmpty() && auth.currentUser != null) {
                    Log.w("ChatViewModel", "Second attempt failed, forcing DB query")

                    // Last resort - direct DB query
                    try {
                        val userId = getCurrentUserId()
                        val conversations = withContext(Dispatchers.IO) {
                            chatDao.getConversationsForUser(userId).first()
                        }
                        Log.d("ChatViewModel", "Direct DB query found ${conversations.size} conversations")

                        // Force one more refresh
                        _userIdFlow.value = ""
                        delay(50)
                        _userIdFlow.value = userId
                    } catch (e: Exception) {
                        Log.e("ChatViewModel", "Error in direct DB query", e)
                    }
                }
            }
        }
    }

    // Add this method to ChatViewModel
    fun forceLoadConversationsAfterLogin() {
        viewModelScope.launch {
            Log.d("ChatViewModel", "Force loading conversations after login")

            // Ensure we're showing conversations
            _showConversations.value = true

            // Make sure we have the correct user ID
            val userId = FirebaseAuth.getInstance().currentUser?.uid
            if (userId == null) {
                Log.e("ChatViewModel", "Cannot load conversations - no user ID available")
                return@launch
            }

            // Force update user ID
            _userIdFlow.value = userId

            // Try to directly query the database
            try {
                // Use IO dispatcher for database operations
                withContext(Dispatchers.IO) {
                    val conversations = chatDao.getConversationsForUser(userId).first()
                    Log.d("ChatViewModel", "Direct query found ${conversations.size} conversations for user $userId")

                    // If we have conversations, select the first one or start new
                    if (conversations.isNotEmpty()) {
                        val firstConvId = conversations.first().id
                        withContext(Dispatchers.Main) {
                            _currentConversationId.value = firstConvId
                            Log.d("ChatViewModel", "Selected conversation $firstConvId")
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            _currentConversationId.value = NEW_CONVERSATION_ID
                            Log.d("ChatViewModel", "No conversations found, starting new")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error directly querying conversations", e)
                _errorMessage.value = "Error loading conversations: ${e.localizedMessage}"
            }
        }
    }

    fun handleLogout() {
        startNewConversation()
        _clearConversationListEvent.value = true
        _showConversations.value = false
        viewModelScope.launch {
            delay(300)
            _clearConversationListEvent.value = false
        }
    }

    fun refreshConversationList() {
        viewModelScope.launch {
            Log.d("ChatViewModel", "Explicitly refreshing conversation list")

            // Clear and reset events
            _clearConversationListEvent.value = true
            delay(100)
            _clearConversationListEvent.value = false

            // Force reload by updating user ID flow
            val currentUserId = getCurrentUserId()
            _userIdFlow.value = ""
            delay(50)
            _userIdFlow.value = currentUserId

            // Log the current state
            Log.d("ChatViewModel", "Refreshed conversation list for user ${_userIdFlow.value}")
        }
    }

    private fun determineConversationType(title: String, id: Long): ConversationType {
        val lowercaseTitle = title.lowercase()
        return when {
            lowercaseTitle.contains("ansiedade") ||
                    lowercaseTitle.contains("medo") ||
                    lowercaseTitle.contains("preocup") -> ConversationType.EMOTIONAL
            lowercaseTitle.contains("depress") ||
                    lowercaseTitle.contains("triste") ||
                    lowercaseTitle.contains("terapia") ||
                    lowercaseTitle.contains("tratamento") -> ConversationType.THERAPEUTIC
            lowercaseTitle.contains("eu") ||
                    lowercaseTitle.contains("minha") ||
                    lowercaseTitle.contains("meu") ||
                    lowercaseTitle.contains("como me") -> ConversationType.PERSONAL
            lowercaseTitle.contains("importante") ||
                    lowercaseTitle.contains("urgente") ||
                    lowercaseTitle.contains("lembrar") -> ConversationType.HIGHLIGHTED
            else -> {
                when ((id % 5)) {
                    0L -> ConversationType.GENERAL
                    1L -> ConversationType.PERSONAL
                    2L -> ConversationType.EMOTIONAL
                    3L -> ConversationType.THERAPEUTIC
                    else -> ConversationType.HIGHLIGHTED
                }
            }
        }
    }

    private fun loadInitialConversationOrStartNew() {
        viewModelScope.launch {
            delay(150)
            _currentConversationId.value = NEW_CONVERSATION_ID
            Log.i("ChatViewModel", "[Init] App iniciado com nova conversa (sem restaurar estado anterior).")
        }
    }

    fun startNewConversation() {
        if (_currentConversationId.value != NEW_CONVERSATION_ID) {
            Log.i("ChatViewModel", "Action: Starting new conversation flow")
            _currentConversationId.value = NEW_CONVERSATION_ID
            _errorMessage.value = null
            _loadingState.value = LoadingState.IDLE
        } else {
            Log.d("ChatViewModel", "Action: Already in new conversation flow, ignoring startNewConversation.")
        }
    }

    fun selectConversation(conversationId: Long) {
        if (conversationId != _currentConversationId.value && conversationId != NEW_CONVERSATION_ID) {
            Log.i("ChatViewModel", "Action: Selecting conversation $conversationId")
            _currentConversationId.value = conversationId
            _errorMessage.value = null
            _loadingState.value = LoadingState.IDLE
        } else if (conversationId == _currentConversationId.value) {
            Log.d("ChatViewModel", "Action: Conversation $conversationId already selected, ignoring selectConversation.")
        } else {
            Log.w("ChatViewModel", "Action: Attempted to select invalid NEW_CONVERSATION_ID ($conversationId), ignoring.")
        }
    }

    fun sendMessage(userMessageText: String) {
        if (userMessageText.isBlank()) {
            Log.w("ChatViewModel", "sendMessage cancelled: Empty message.")
            return
        }
        if (_loadingState.value == LoadingState.LOADING) {
            Log.w("ChatViewModel", "sendMessage cancelled: Already loading.")
            _errorMessage.value = "Aguarde a resposta anterior."
            return
        }
        _loadingState.value = LoadingState.LOADING
        _errorMessage.value = null

        val timestamp = System.currentTimeMillis()
        var targetConversationId = _currentConversationId.value
        val isStartingNewConversation = (targetConversationId == null || targetConversationId == NEW_CONVERSATION_ID)

        if (isStartingNewConversation) {
            targetConversationId = timestamp
            Log.i("ChatViewModel", "Action: Creating new conversation with potential ID: $targetConversationId")
            _currentConversationId.value = targetConversationId
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    metadataDao.insertOrUpdateMetadata(
                        ConversationMetadataEntity(
                            conversationId = targetConversationId,
                            customTitle = null,
                            userId = _userIdFlow.value
                        )
                    )
                } catch (e: Exception) {
                    Log.e("ChatViewModel", "Error saving initial metadata for new conv $targetConversationId", e)
                }
            }
        }

        if (targetConversationId == null || targetConversationId == NEW_CONVERSATION_ID) {
            Log.e("ChatViewModel", "sendMessage Error: Invalid targetConversationId ($targetConversationId) after checking for new conversation.")
            _errorMessage.value = "Erro interno: Não foi possível determinar a conversa."
            _loadingState.value = LoadingState.IDLE
            return
        }

        val userUiMessage = com.ivip.brainstormia.ChatMessage(userMessageText, Sender.USER)
        saveMessageToDb(userUiMessage, targetConversationId, timestamp)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentMessagesFromDb = chatDao.getMessagesForConversation(targetConversationId, _userIdFlow.value).first()
                val historyMessages = mapEntitiesToUiMessages(currentMessagesFromDb)

                Log.d("ChatViewModel", "API Call: Enviando ${historyMessages.size} mensagens para a API para conv $targetConversationId")

                callOpenAIApi(userMessageText, historyMessages, targetConversationId)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error preparing history or calling API for conv $targetConversationId", e)
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Erro ao processar histórico ou chamar IA: ${e.message}"
                    _loadingState.value = LoadingState.ERROR
                }
            }
        }
    }

    fun deleteConversation(conversationId: Long) {
        if (conversationId == NEW_CONVERSATION_ID) {
            Log.w("ChatViewModel", "Attempted to delete invalid NEW_CONVERSATION_ID conversation.")
            return
        }
        Log.i("ChatViewModel", "Action: Deleting conversation $conversationId and its metadata")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                chatDao.clearConversation(conversationId, _userIdFlow.value)
                metadataDao.deleteMetadata(conversationId)
                Log.i("ChatViewModel", "Conversation $conversationId and metadata deleted successfully from DB.")
                if (_currentConversationId.value == conversationId) {
                    val remainingConversations = chatDao.getConversationsForUser(_userIdFlow.value).first()
                    withContext(Dispatchers.Main) {
                        val nextConversationId = remainingConversations.firstOrNull()?.id
                        if (nextConversationId != null) {
                            Log.i("ChatViewModel", "Deleted current conversation, selecting next available from DB: $nextConversationId")
                            _currentConversationId.value = nextConversationId
                        } else {
                            Log.i("ChatViewModel", "Deleted current conversation, no others left in DB. Starting new conversation flow.")
                            _currentConversationId.value = NEW_CONVERSATION_ID
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error deleting conversation $conversationId or its metadata", e)
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Erro ao excluir conversa: ${e.localizedMessage}"
                }
            }
        }
    }

    fun renameConversation(conversationId: Long, newTitle: String) {
        if (conversationId == NEW_CONVERSATION_ID) {
            Log.w("ChatViewModel", "Cannot rename NEW_CONVERSATION_ID.")
            _errorMessage.value = "Não é possível renomear uma conversa não salva."
            return
        }
        val trimmedTitle = newTitle.trim()
        if (trimmedTitle.isBlank()) {
            Log.w("ChatViewModel", "Cannot rename conversation $conversationId to blank title.")
            _errorMessage.value = "O título não pode ficar em branco."
            return
        }
        Log.i("ChatViewModel", "Action: Renaming conversation $conversationId to '$trimmedTitle'")
        val metadata = ConversationMetadataEntity(
            conversationId = conversationId,
            customTitle = trimmedTitle,
            userId = _userIdFlow.value
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                metadataDao.insertOrUpdateMetadata(metadata)
                Log.i("ChatViewModel", "Conversation $conversationId renamed successfully in DB.")
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error renaming conversation $conversationId", e)
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Erro ao renomear conversa: ${e.localizedMessage}"
                }
            }
        }
    }

    // Substitua o método callOpenAIApi por esta versão simplificada:

    // Este é o método completo que deve ser adicionado ao ChatViewModel.kt

    private suspend fun callOpenAIApi(userMessageText: String, historyMessages: List<com.ivip.brainstormia.ChatMessage>, conversationId: Long) {
        try {
            val currentModel = _selectedModel.value
            Log.d("ChatViewModel", "Iniciando chamada OpenAI com modelo ${currentModel.displayName} para conv $conversationId")

            var responseText = StringBuilder()
            var modelUsed = currentModel

            withContext(Dispatchers.IO) {
                try {
                    // Usar o OpenAIClient para gerar completions com timeout
                    val result = withTimeoutOrNull(60000) { // Timeout de 60 segundos
                        openAIClient.generateChatCompletion(
                            modelId = currentModel.apiEndpoint,
                            systemPrompt = brainstormiaSystemPrompt,
                            userMessage = userMessageText,
                            historyMessages = historyMessages
                        ).collect { chunk ->
                            responseText.append(chunk)
                        }
                        true // Indica que o fluxo foi concluído com sucesso
                    }

                    if (result == null) {
                        // Timeout ocorreu
                        Log.w("ChatViewModel", "Timeout com modelo ${currentModel.id}")

                        // Se não for o modelo GPT-4o padrão, tentar com ele
                        if (currentModel.id != "gpt-4o") {
                            responseText.clear() // Limpar qualquer resposta parcial
                            Log.w("ChatViewModel", "Tentando com modelo GPT-4o")
                            val backupModel = availableModels.first { it.id == "gpt-4o" }
                            modelUsed = backupModel

                            // Tentar com GPT-4o
                            val backupResult = withTimeoutOrNull(60000) {
                                openAIClient.generateChatCompletion(
                                    modelId = backupModel.apiEndpoint,
                                    systemPrompt = brainstormiaSystemPrompt,
                                    userMessage = userMessageText,
                                    historyMessages = historyMessages
                                ).collect { chunk ->
                                    responseText.append(chunk)
                                }
                                true
                            }

                            if (backupResult == null) {
                                // Timeout com o modelo de backup também
                                throw Exception("Timeout na chamada da API (segunda tentativa)")
                            }
                        } else {
                            throw Exception("Timeout na chamada da API")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ChatViewModel", "Erro na chamada à API: ${e.message}")
                    throw e
                }
            }

            val finalResponse = responseText.toString()
            if (finalResponse.isNotBlank()) {
                Log.d("ChatViewModel", "Resposta da OpenAI recebida para conv $conversationId (${finalResponse.length} caracteres)")

                // Criar a entidade da mensagem e inseri-la diretamente no banco de dados
                val botMessageEntity = ChatMessageEntity(
                    id = 0, // ID automático
                    conversationId = conversationId,
                    text = finalResponse,
                    sender = Sender.BOT.name,
                    timestamp = System.currentTimeMillis(),
                    userId = _userIdFlow.value
                )

                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        chatDao.insertMessage(botMessageEntity)
                        Log.d("ChatViewModel", "Mensagem do bot salva no banco de dados para conv $conversationId")
                    } catch (e: Exception) {
                        Log.e("ChatViewModel", "Erro ao salvar mensagem do bot no banco de dados", e)
                    }
                }
            } else {
                Log.w("ChatViewModel", "Resposta vazia da OpenAI para conv $conversationId")
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Resposta vazia da IA. Por favor, tente novamente."
                }
            }
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Erro na chamada à API OpenAI para conv $conversationId", e)
            withContext(Dispatchers.Main) {
                if (e.message?.contains("Timeout") == true) {
                    _errorMessage.value = "A IA demorou muito para responder. Por favor, tente novamente."
                } else {
                    _errorMessage.value = "Erro ao comunicar com IA: ${e.localizedMessage}"
                }
            }
        } finally {
            withContext(Dispatchers.Main) {
                _loadingState.value = LoadingState.IDLE
            }
        }
    }

    private fun mapEntitiesToUiMessages(entities: List<ChatMessageEntity>): List<com.ivip.brainstormia.ChatMessage> {
        return entities.mapNotNull { entity ->
            try {
                val sender = enumValueOf<Sender>(entity.sender.uppercase())
                com.ivip.brainstormia.ChatMessage(entity.text, sender)
            } catch (e: IllegalArgumentException) {
                Log.e("ChatViewModelMapper", "Invalid sender string in DB: ${entity.sender}. Skipping message ID ${entity.id}.")
                null
            }
        }
    }

    private fun mapUiMessageToEntity(message: com.ivip.brainstormia.ChatMessage, conversationId: Long, timestamp: Long): ChatMessageEntity {
        return ChatMessageEntity(
            conversationId = conversationId,
            text = message.text,
            sender = message.sender.name,
            timestamp = timestamp,
            userId = _userIdFlow.value
        )
    }

    private fun generateFallbackTitleSync(conversationId: Long): String {
        return try {
            runCatching {
                runBlocking {
                    generateFallbackTitle(conversationId)
                }
            }.getOrElse { ex ->
                Log.e("ChatViewModel", "Error generating fallback title synchronously for conv $conversationId", ex)
                "Conversa $conversationId"
            }
        } catch (e: Exception) {
            "Conversa $conversationId"
        }
    }

    private suspend fun generateFallbackTitle(conversationId: Long): String = withContext(Dispatchers.IO) {
        try {
            val firstUserMessageText = chatDao.getFirstUserMessageText(conversationId, _userIdFlow.value)
            if (!firstUserMessageText.isNullOrBlank()) {
                Log.d("ChatViewModel", "Generating fallback title for $conversationId using first message.")
                return@withContext firstUserMessageText.take(30) + if (firstUserMessageText.length > 30) "..." else ""
            } else {
                try {
                    Log.d("ChatViewModel", "Generating fallback title for $conversationId using date.")
                    return@withContext "Conversa ${titleDateFormatter.format(Date(conversationId))}"
                } catch (formatException: Exception) {
                    Log.w("ChatViewModel", "Could not format conversationId $conversationId as Date for fallback title.", formatException)
                    return@withContext "Conversa $conversationId"
                }
            }
        } catch (dbException: Exception) {
            Log.e("ChatViewModel", "Error generating fallback title for conv $conversationId", dbException)
            return@withContext "Conversa $conversationId"
        }
    }

    suspend fun getDisplayTitle(conversationId: Long): String {
        return withContext(Dispatchers.IO) {
            if (conversationId == NEW_CONVERSATION_ID) {
                "Nova Conversa"
            } else {
                try {
                    val customTitle = metadataDao.getCustomTitle(conversationId)
                    if (!customTitle.isNullOrBlank()) {
                        Log.d("ChatViewModel", "Using custom title for $conversationId: '$customTitle'")
                        customTitle
                    } else {
                        generateFallbackTitle(conversationId)
                    }
                } catch (dbException: Exception) {
                    Log.e("ChatViewModel", "Error fetching title data for conv $conversationId", dbException)
                    "Conversa $conversationId"
                }
            }
        }
    }

    private fun saveMessageToDb(uiMessage: com.ivip.brainstormia.ChatMessage, conversationId: Long, timestamp: Long) {
        val entity = mapUiMessageToEntity(uiMessage, conversationId, timestamp)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                chatDao.insertMessage(entity)
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error saving message to DB", e)
            }
        }
    }



    companion object {
        private val titleDateFormatter = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    }
}