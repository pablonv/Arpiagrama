package com.example.arpiagrama.operational.ui;

import com.example.arpiagrama.R;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.app.PictureInPictureParams;
import android.util.Rational;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Rect;
import android.graphics.pdf.PdfDocument;
import android.text.TextUtils;
import android.view.Surface;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.example.arpiagrama.operational.rendering.BorderedText;
import com.example.arpiagrama.operational.rendering.MultiBoxTracker;
import com.example.arpiagrama.operational.rendering.OverlayView;
import com.example.arpiagrama.operational.context.ContextDescriptionManager;
import com.example.arpiagrama.acquisition.camera.CameraConnectionFragment;
import com.example.arpiagrama.acquisition.camera.ImageUtils;
import com.example.arpiagrama.visualprocessing.infrastructure.HandDetectorHelper;
import com.example.arpiagrama.visualprocessing.infrastructure.HandDetectorHelper.HandOcclusion;
import com.example.arpiagrama.visualprocessing.tracking.PieceTracker;
import com.example.arpiagrama.visualprocessing.model.Recognition;
import com.example.arpiagrama.visualprocessing.infrastructure.YoloV8TfliteDetector;
import com.example.arpiagrama.operational.audio.SpeechController;

import android.text.InputType;
import android.text.TextWatcher;
import android.text.Editable;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.graphics.RectF;
import android.graphics.PointF;

import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * MainActivity com:
 * - seleção automática de câmera 0.5x (ultra-wide) se existir;
 * - preview FIT (feito no CameraConnectionFragment);
 * - todo o seu fluxo anterior de detecção/nomeação permanece igual.
 *
 * OBS: este arquivo é baseado na sua versão mais recente com TTS, diálogo, etc.
 *      Ajuste getters de Recognition se necessário (getTitle / getLabel...).
 */
public class MainActivity extends BaseActivity
        implements ImageReader.OnImageAvailableListener,
        CameraConnectionFragment.ZoomListener {

    private Handler handler;
    private Matrix frameToCropTransform;
    private Matrix cropToFrameTransform;
    private int sensorOrientation;
    private static final int TF_OD_API_INPUT_SIZE = 320;
    private static final boolean MAINTAIN_ASPECT = false;
    private static final float TEXT_SIZE_DIP = 10;
    private static final int PERMISSION_CODE = 321;
    private static final int AUDIO_PERMISSION_CODE = 322;
    private static final long CONFIRMATION_NO_RESPONSE_DELAY_MS = 10000L;
    private static final long NAME_LISTEN_WINDOW_MS = 5000L;
    private static final long NAME_CONFIRMATION_LISTEN_WINDOW_MS = 6000L;
    private static final long MIN_MIC_LISTEN_WINDOW_MS = 4000L;

    private static final Size DEFAULT_PREVIEW_SIZE = new Size(1280, 720);

    private final CameraSelectorStrategy cameraSelector = new BackCameraFirstSelector();

    private OverlayView trackingOverlay;
    private BorderedText borderedText;
    private MultiBoxTracker tracker;

    private YoloV8TfliteDetector yoloDetector;
    private HandDetectorHelper handDetectorHelper;
    private PieceTracker pieceTracker;
    private static final float MIN_CONFIDENCE = 0.80f;

    private SpeechController speechController;
    private ContextDescriptionManager contextDescriptionManager;
    private AlertDialog exportProgressDialog;
    private SpeechRecognizer speechRecognizer;
    private Intent speechRecognizerIntent;
    private EditText pendingVoiceInputTarget;
    private boolean listeningForInteractiveDescription = false;
    private boolean pendingInteractiveDescriptionRequest = false;
    private boolean awaitingNameConfirmation = false;
    private boolean listeningForNameConfirmation = false;
    private boolean namePromptSpoken = false;
    private boolean listeningForExportFormat = false;
    private boolean pendingExportFormatRequest = false;
    private boolean listeningForRelationshipType = false;
    private boolean pendingRelationshipTypeRequest = false;
    private boolean listeningForExternalHelpConfirmation = false;
    private boolean pendingExternalHelpConfirmationRequest = false;
    private Runnable confirmationNoResponseRunnable = null;
    private Runnable nameListeningTimeoutRunnable = null;
    private boolean ignoreNextNameError = false;
    private String lastSpokenName = null;
    private boolean nomeDefinidoPorVoz = false;
    private boolean isListeningName = false;
    private static final String INTERACTIVE_DESCRIPTION_COMMAND = "descricao contextual";
    private static final String JITSI_HELP_ROOM_URL = "https://meet.jit.si/ArpiagramaAjudaExterna";
    private static final String JITSI_MEET_PACKAGE = "org.jitsi.meet";

    private static final long STABILITY_MS = 3000L;
    private static final float CENTER_TOL_FRAC = 0.12f;

    private static final long FREEZE_RECHECK_INTERVAL_MS = 5000L;
    private static final long SAME_TYPE_RECHECK_DELAY_MS = 4000L;
    private static final int REMOVE_MISSING_FRAME_LIMIT = 8;
    private static final float PRESENCE_IOU_THR = 0.40f;
    private static final float HAND_OVERLAP_IOU_THR = 0.20f;
    private static final float OCCLUSION_OVERLAP_IOU_THR = 0.08f;
    private static final float OCCLUSION_CENTER_TOL_FRAC = 0.45f;
    private static final float RELATIONSHIP_REASSOC_DIST_FRAC = 0.8f;
    private static final float PRESENCE_CENTER_TOL_FRAC = 0.35f;
    private static final float PRESENCE_MIN_COVERAGE_THR = 0.60f;
    private static final float FROZEN_OVERLAP_MIN_COVERAGE_THR = 0.65f;
    private static final long NAME_REMINDER_DELAY_MS = 10_000L;
    private static final long HAND_PERSISTENCE_MS = 15000L;
    private static final long HAND_DOWNLOAD_COOLDOWN_MS = 500L;
    private static final long INDEX_TIP_PERSISTENCE_MS = 900L;
    private static final long MULTI_PIECE_WARN_THRESHOLD_MS = 5000L;
    private static final long MULTI_PIECE_REPEAT_INTERVAL_MS = 10000L;
    private static final String HAND_PERSISTENCE_MESSAGE = "Para que evitar não detecção ou exclusão da peça, fique tateando por no máximo 1 minuto";
    private static final String DATASET_CONFIG_ASSET = "data.yaml";
    private static final float ROI_WIDTH_FRACTION = 0.8f;
    private static final float ROI_HEIGHT_FRACTION = 0.9f;

    private static final String TAG = "MainActivity";
    private static final String DETECTION_STATE_PREFS = "detection_state";
    private static final String DETECTION_STATE_KEY = "frozen_pieces";
    private static final String APP_NAV_STATE_PREFS = "app_navigation_state";
    private static final String EXTERNAL_HELP_ACTIVE_KEY = "external_help_active";
    private final ToneGenerator toneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70);

    private void run() {
        if (interactionState == InteractionState.DEFININDO_PECA || dialogoNomeAberto()) {
            return;
        }
        if (!lastPieceActivityWasDefinition) {
            return;
        }
        if (totalPecasDefinidas == 0 || totalPecasDefinidas % 3 != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long elapsed = now - lastPieceActivityMs;
        if (elapsed < NAME_REMINDER_DELAY_MS) {
            nameReminderHandler.postDelayed(nameReminderRunnable, NAME_REMINDER_DELAY_MS - elapsed);
            return;
        }
        falarEmFila("Você pode clicar em descrição contextual para ouvir a descrição do diagrama gerado por IA (aperte F1), solicitar ajuda externa (aperte F2) ou em salvar para baixar em PNG/PDF ou compartilhar (aperte F3) .");
    }

    //1 imagem ~ 0,004
    private enum SaveFormat {
        PNG,
        PDF
    }

    private enum PieceState {
        ATIVA,
        OCLUIDA,
        REMOVIDA
    }

    private static class PiecePersistentState {
        String tipo;
        RectF ultimaPosicao;
        int contadorAusencia;
        String nomeDefinido;
        PieceState estado;

        PiecePersistentState(String tipo, RectF box) {
            this.tipo = tipo;
            this.ultimaPosicao = box != null ? new RectF(box) : null;
            this.contadorAusencia = 0;
            this.nomeDefinido = "";
            this.estado = PieceState.ATIVA;
        }
    }

    private enum InteractionState {
        AGUARDANDO_PECA,
        PECA_TRAVADA,
        DEFININDO_PECA
    }

    private InteractionState interactionState = InteractionState.AGUARDANDO_PECA;
    private RectF lastBox = null;
    private String lastStableLabel = null;
    private long stableSinceMs = 0L;
    private final Paint roiPaint = new Paint();

    private final List<RectF> frozenBoxes = new ArrayList<>();
    private final List<String> frozenLabels = new ArrayList<>();
    private final List<Long> frozenLastSeenMs = new ArrayList<>();
    private final List<Integer> frozenMissingFrames = new ArrayList<>();
    private final List<String> frozenTypes = new ArrayList<>();
    private final List<Long> delayedSameTypeCheckSinceMs = new ArrayList<>();
    private final List<RectF> delayedSameTypeCandidateBoxes = new ArrayList<>();
    private final List<RectF> exportFrozenBoxes = new ArrayList<>();
    private final List<String> exportFrozenLabels = new ArrayList<>();
    private final List<Long> exportFrozenLastSeenMs = new ArrayList<>();
    private final List<Integer> exportFrozenMissingFrames = new ArrayList<>();
    private final List<String> exportFrozenTypes = new ArrayList<>();

    private boolean nameDialogShown = false;
    private AlertDialog currentNameDialog = null;
    private int pendingNameIndex = -1;
    private String pendingClassSpoken = null;
    private static final float PENDING_PIECE_EXCLUSION_IOU = 0.35f;

    private AlertDialog removalDecisionDialog = null;
    private boolean listeningForRemovalDecision = false;
    private boolean pendingRemovalDecisionRequest = false;
    private String pendingRemovalName = null;
    private String pendingRemovalType = null;

    private String pendingRelocationName = null;
    private String pendingRelocationType = null;
    private RectF relocationLastBox = null;
    private long relocationStableSinceMs = 0L;

    private Runnable listeningWindowTimeoutRunnable = null;
    private ImageView micIndicatorWindow;
    private AlertDialog externalHelpConfirmationDialog = null;
    private boolean dialogTabNavigationStarted = false;

    private long multiPieceDetectedSinceMs = 0L;
    private boolean multiPieceWarningSent = false;
    private long multiPieceLastWarningMs = 0L;
    private boolean lazySameTypeCheckActive = false;
    private RectF lazySameTypeCandidateBox = null;
    private String lazySameTypeRawLabel = null;
    private String lazySameTypeType = null;
    private final List<Integer> lazyNamedIndicesSnapshot = new ArrayList<>();
    private final List<RectF> lazyNamedBoxesSnapshot = new ArrayList<>();

    private boolean isProcessingFrame = false;
    private final byte[][] yuvBytes = new byte[3][];
    private int[] rgbBytes = null;
    private int yRowStride;

    private ActivityResultLauncher<Intent> createDocumentLauncher;
    private Bitmap pendingCombinedBitmap = null;
    private Bitmap pendingExportChoiceBitmap = null;
    private SaveFormat pendingSaveFormat = null;
    private Runnable postInferenceCallback;
    private Runnable imageConverter;
    private Bitmap rgbFrameBitmap;
    private Bitmap roiMaskedBitmap;
    private Bitmap croppedBitmap;
    private int previewHeight = 0, previewWidth = 0;
    private final Object latestFrameLock = new Object();
    private Bitmap latestFrameForExport;
    private boolean detectionPaused = false;
    private boolean detectionPausedForExport = false;
    private boolean detectionPausedForContextDescription = false;
    private boolean zoomDetectionFrozen = false;
    private boolean pendingZoomStateVerification = false;
    private boolean pendingShareReturn = false;
    private boolean exportStateCaptured = false;
    private RectF detectionRoi = null;
    private final List<RectF> detectedHandBoxes = new ArrayList<>();
    private final List<PointF> detectedIndexTips = new ArrayList<>();
    private final List<PointF> cachedIndexTips = new ArrayList<>();
    private long lastIndexTipDetectionMs = 0L;
    private final List<Recognition> latestDetectionsSnapshot = new ArrayList<>();
    private final List<RectF> occlusionRegions = new ArrayList<>();
    private final List<PiecePersistentState> pieceStates = new ArrayList<>();
    private long handsSeenSinceMs = 0L;
    private long lastHandsSeenMs = 0L;
    private boolean handWarningShown = false;
    private AlertDialog exportFormatDialog = null;

    private long lastPieceActivityMs = 0L;
    private boolean lastPieceActivityWasDefinition = false;
    private int totalPecasDefinidas = 0;

    private final Handler nameReminderHandler = new Handler(Looper.getMainLooper());
    private final Runnable nameReminderRunnable = this::run;
    private final Runnable contextDescriptionDetectionPauseWatcher = new Runnable() {
        @Override
        public void run() {
            if (!detectionPausedForContextDescription) {
                return;
            }
            if (isFluxoDescricaoContextualAtivo()) {
                if (handler != null) {
                    handler.postDelayed(this, 120L);
                }
                return;
            }
            retomarDeteccaoAposDescricaoContextual();
        }
    };

    private boolean welcomeMessageSpoken = false;
    private final Set<String> rotulosNd = new HashSet<>();
    private ZoomSnapshot zoomSnapshotBeforeFreeze = null;

    private Handler freezeHandler;
    private boolean freezeWatcherSuspended = false;
    private boolean preserveNamedPiecesDuringExternalHelp = false;
    private final Runnable freezeWatcher = new Runnable() {
        @Override public void run() {
            if (detectionPausedForExport || freezeWatcherSuspended) {
                if (freezeHandler != null) {
                    freezeHandler.postDelayed(this, FREEZE_RECHECK_INTERVAL_MS);
                }
                return;
            }
            try {
                long now = System.currentTimeMillis();
                List<RectF> handSnapshot = copiarMaos();
                List<RectF> occlusionSnapshot = copiarRegioesOclusao();
                for (int i = frozenBoxes.size() - 1; i >= 0; i--) {
                    int missingFrames = (i < frozenMissingFrames.size()) ? frozenMissingFrames.get(i) : 0;
                    if (missingFrames >= REMOVE_MISSING_FRAME_LIMIT) {
                        String removedName = frozenLabels.get(i);
                        String removedType = (i < frozenTypes.size()) ? frozenTypes.get(i) : null;
                        boolean namedPiece = removedName != null && !removedName.isEmpty();
                        if (preserveNamedPiecesDuringExternalHelp && namedPiece) {
                            frozenLastSeenMs.set(i, now);
                            continue;
                        }
                        RectF frozenBox = frozenBoxes.get(i);
                        if (frozenBox != null && (maoSobrepoePecaDefinida(frozenBox, handSnapshot) || emAreaDeOclusao(frozenBox, occlusionSnapshot))) {
                            frozenMissingFrames.set(i, 0);
                            continue;
                        }
                        if (interactionState == InteractionState.DEFININDO_PECA && namedPiece && i != pendingNameIndex) {
                            continue;
                        }
                        removerPecaCongelada(i);

                        interromperAudiosParaAcaoDePeca();
                        if (namedPiece) {
                            falarEmFila("Peça " + formatarTipoPeca(removedType) + " \"" + removedName + "\" removida do quadro.");
                        } else {
                            falarEmFila(mensagemPecaRemovida(removedType));
                        }
                    }
                }
            } finally {
                if (freezeHandler != null) {
                    freezeHandler.postDelayed(this, FREEZE_RECHECK_INTERVAL_MS);
                }
            }
        }
    };

    private static class ZoomSnapshot {
        private final List<RectF> boxes = new ArrayList<>();
        private final List<String> labels = new ArrayList<>();
        private final List<String> types = new ArrayList<>();

        private ZoomSnapshot(List<RectF> sourceBoxes, List<String> sourceLabels, List<String> sourceTypes) {
            if (sourceBoxes != null) {
                for (RectF box : sourceBoxes) {
                    boxes.add(box != null ? new RectF(box) : null);
                }
            }
            if (sourceLabels != null) {
                labels.addAll(sourceLabels);
            }
            if (sourceTypes != null) {
                types.addAll(sourceTypes);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        handler = new Handler();
        pieceTracker = new PieceTracker(
                STABILITY_MS,
                FREEZE_RECHECK_INTERVAL_MS,
                SAME_TYPE_RECHECK_DELAY_MS,
                10f,
                PieceTracker.MAX_CENTER_DISTANCE_RELATIONSHIP
        );

        createDocumentLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                this::onCreateDocumentResult
        );

        configurarBotao(R.id.button_context_description, this::solicitarDescricaoContextual);
        configurarBotao(R.id.button_download_image, this::solicitarDownloadQuadroAtual);
        configurarBotao(R.id.button_external_help, this::solicitarConfirmacaoAjudaExterna);
        micIndicatorWindow = findViewById(R.id.mic_indicator_window);
        atualizarIndicadorMicrofone(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M){
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED){
                String[] permission = {Manifest.permission.CAMERA};
                requestPermissions(permission, PERMISSION_CODE);
            } else {
                configurarFragmento();
            }
        } else {
            configurarFragmento();
        }

        tracker = new MultiBoxTracker(this);
        carregarRotulosDataset();

        try {
            yoloDetector = new YoloV8TfliteDetector(
                    getApplicationContext(),
                    "best_float16.tflite",
                    "data.yaml",
                    0.8f, // CONF_THRES
                    0.45f, // IOU_THRES
                    4      // threads
            );
        } catch (IOException e) {
            Log.e("MainActivity", "Erro carregando YOLO TFLite", e);
        }
        handDetectorHelper = new HandDetectorHelper(getApplicationContext());

        speechController = new SpeechController(getApplicationContext());
        contextDescriptionManager = new ContextDescriptionManager(this, speechController);
        configurarReconhecimentoVoz();

        freezeHandler = new Handler();
        freezeHandler.postDelayed(freezeWatcher, FREEZE_RECHECK_INTERVAL_MS);
        restaurarEstadoDeteccaoPersistido();
    }

    private void carregarRotulosDataset() {
        rotulosNd.clear();
        List<String> nomes = lerNomesDataYaml();
        for (String nome : nomes) {
            if ("nd".equals(normalizarRotulo(nome))) {
                rotulosNd.add(normalizarRotulo(nome));
            }
        }
        rotulosNd.add("nd");
    }

    private List<String> lerNomesDataYaml() {
        List<String> nomes = new ArrayList<>();
        try (InputStream inputStream = getAssets().open(DATASET_CONFIG_ASSET);
             InputStreamReader reader = new InputStreamReader(inputStream);
             BufferedReader bufferedReader = new BufferedReader(reader)) {
            boolean lendoNomes = false;
            String linha;
            while ((linha = bufferedReader.readLine()) != null) {
                String conteudo = linha.trim();
                if (!lendoNomes) {
                    if (conteudo.equals("names:")) {
                        lendoNomes = true;
                    }
                    continue;
                }
                if (conteudo.startsWith("-")) {
                    String nome = conteudo.substring(1).trim();
                    if (!nome.isEmpty()) {
                        nomes.add(nome);
                    }
                } else if (!conteudo.isEmpty()) {
                    break;
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Não foi possível ler data.yaml para carregar classes do dataset.", e);
        }
        return nomes;
    }

    @Override
    protected void onResume() {
        super.onResume();
        limparEstadoAjudaExternaAtiva();
        freezeWatcherSuspended = false;
        preserveNamedPiecesDuringExternalHelp = false;
        restaurarEstadoDeteccaoPersistido();
        if (pendingShareReturn) {
            pendingShareReturn = false;
            liberarBitmapPendente();
            retomarDeteccaoAposExportacao();
        }
    }

    @Override
    protected boolean onFunctionKeyPressed(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_F1) {
            solicitarDescricaoContextual();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_F2) {
            solicitarConfirmacaoAjudaExterna();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_F3) {
            solicitarDownloadQuadroAtual();
            return true;
        }
        return super.onFunctionKeyPressed(keyCode);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() == KeyEvent.KEYCODE_TAB
                && handleDialogTabNavigation(event)) {
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
            if (dialogoNomeAberto()) {
                cancelarDialogoDefinicaoPeca();
                return true;
            }
            cancelarAudioAtivo();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }



    private void configurarBotao(int buttonId, Runnable action) {
        Button botao = findViewById(buttonId);
        if (botao != null && action != null) {
            setButtonAction(botao, action);
        }
    }

    private void solicitarDescricaoContextual() {
        if (contextDescriptionManager == null) {
            return;
        }
        if (contextDescriptionManager.isRequestInProgress()) {
            mostrarToastCurto(R.string.context_description_in_progress);
            return;
        }

        String structuredDiagramData = montarDadosEstruturadosDoDiagrama();
        pausarDeteccaoParaDescricaoContextual();
        contextDescriptionManager.requestDescription(structuredDiagramData);
    }

    private void pausarDeteccaoParaDescricaoContextual() {
        detectionPausedForContextDescription = true;
        detectionPaused = true;
        monitorarPausaDeteccaoDescricaoContextual();
    }

    private void monitorarPausaDeteccaoDescricaoContextual() {
        if (handler == null) {
            return;
        }
        handler.removeCallbacks(contextDescriptionDetectionPauseWatcher);
        handler.post(contextDescriptionDetectionPauseWatcher);
    }

    private boolean isFluxoDescricaoContextualAtivo() {
        return contextDescriptionManager != null && contextDescriptionManager.isInteractionActive();
    }

    private String montarDadosEstruturadosDoDiagrama() {
        List<RectF> boxesSnapshot = new ArrayList<>();
        List<String> labelsSnapshot = new ArrayList<>();
        List<String> typesSnapshot = new ArrayList<>();
        List<Recognition> detectionsSnapshot = new ArrayList<>();
        List<PointF> indexTipsSnapshot = copiarPontosIndicador();

        synchronized (frozenBoxes) {
            for (RectF box : frozenBoxes) {
                boxesSnapshot.add(new RectF(box));
            }
            labelsSnapshot.addAll(frozenLabels);
            typesSnapshot.addAll(frozenTypes);
        }

        synchronized (latestDetectionsSnapshot) {
            for (Recognition recognition : latestDetectionsSnapshot) {
                if (recognition == null || recognition.getLocation() == null) continue;
                detectionsSnapshot.add(new Recognition(
                        recognition.getTitle(),
                        recognition.getConfidence(),
                        recognition.getId(),
                        new RectF(recognition.getLocation()),
                        recognition.getName(),
                        recognition.getType(),
                        recognition.isDefined(),
                        recognition.isBeingDefined()
                ));
            }
        }

        JSONObject payload = new JSONObject();
        JSONArray pieces = new JSONArray();
        Map<Integer, String> idsPorIndice = new HashMap<>();

        try {
            for (int i = 0; i < boxesSnapshot.size(); i++) {
                JSONObject piece = new JSONObject();
                String pieceId = "P" + (i + 1);
                idsPorIndice.put(i, pieceId);

                String tipo = i < typesSnapshot.size() ? typesSnapshot.get(i) : null;
                String nome = i < labelsSnapshot.size() ? labelsSnapshot.get(i) : null;
                String nomeFinal = (nome == null || nome.trim().isEmpty()) ? "sem nome definido" : nome.trim();

                piece.put("id", pieceId);
                piece.put("tipo", (tipo == null || tipo.trim().isEmpty()) ? "tipo não confirmado" : tipo.trim());
                piece.put("nome", nomeFinal);
                pieces.put(piece);
            }

            JSONArray conexoes = montarConexoesEstruturadas(boxesSnapshot, typesSnapshot, idsPorIndice);
            JSONObject moeda = detectarMoeda(detectionsSnapshot);
            String pecaAlvoIndicador = encontrarPecaMaisProximaDoIndicador(boxesSnapshot, indexTipsSnapshot, idsPorIndice);

            payload.put("modo", moeda == null ? "sem_moeda" : "com_moeda");
            payload.put("pecas", pieces);
            payload.put("conexoes", conexoes);
            payload.put("moeda", moeda == null ? JSONObject.NULL : moeda);
            payload.put("dedo_indicador", indexTipsSnapshot.isEmpty() ? JSONObject.NULL : serializarIndicadores(indexTipsSnapshot));
            payload.put("peca_alvo_indicador", pecaAlvoIndicador == null ? JSONObject.NULL : pecaAlvoIndicador);

            if (moeda != null) {
                String alvoId = encontrarPecaMaisProximaDaMoeda(boxesSnapshot, moeda.optDouble("centroX", Double.NaN), moeda.optDouble("centroY", Double.NaN), idsPorIndice);
                payload.put("peca_alvo_moeda", alvoId == null ? JSONObject.NULL : alvoId);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Erro ao montar payload de descrição contextual", e);
        }

        return payload.toString();
    }

    private JSONArray montarConexoesEstruturadas(List<RectF> boxesSnapshot,
                                                 List<String> typesSnapshot,
                                                 Map<Integer, String> idsPorIndice) {
        JSONArray conexoes = new JSONArray();
        List<Integer> endpoints = new ArrayList<>();
        List<Integer> relacionamentos = new ArrayList<>();

        for (int i = 0; i < boxesSnapshot.size(); i++) {
            String tipo = (i < typesSnapshot.size() && typesSnapshot.get(i) != null) ? typesSnapshot.get(i).trim() : "";
            if ("relacionamento".equals(tipo)) relacionamentos.add(i);
            else endpoints.add(i);
        }

        for (int relIndex : relacionamentos) {
            int primeiro = -1;
            int segundo = -1;
            float melhor = Float.MAX_VALUE;
            float segundoMelhor = Float.MAX_VALUE;

            for (int endpointIndex : endpoints) {
                float distancia = distanciaCentros(boxesSnapshot.get(relIndex), boxesSnapshot.get(endpointIndex));
                if (distancia < melhor) {
                    segundoMelhor = melhor;
                    segundo = primeiro;
                    melhor = distancia;
                    primeiro = endpointIndex;
                } else if (distancia < segundoMelhor) {
                    segundoMelhor = distancia;
                    segundo = endpointIndex;
                }
            }

            if (primeiro >= 0 && segundo >= 0) {
                JSONObject conexao = new JSONObject();
                try {
                    conexao.put("relacionamento", idsPorIndice.get(relIndex));
                    conexao.put("origem", idsPorIndice.get(primeiro));
                    conexao.put("destino", idsPorIndice.get(segundo));
                    conexoes.put(conexao);
                } catch (JSONException e) {
                    Log.w(TAG, "Erro ao montar conexão estruturada", e);
                }
            }
        }
        return conexoes;
    }

    private JSONObject detectarMoeda(List<Recognition> detectionsSnapshot) {
        for (Recognition recognition : detectionsSnapshot) {
            if (recognition == null || recognition.getLocation() == null) continue;
            String label = recognition.getTitle();
            String normalizado = normalizarRotulo(label);
            if (normalizado.contains("moeda") || normalizado.contains("coin")) {
                RectF loc = recognition.getLocation();
                JSONObject moeda = new JSONObject();
                try {
                    moeda.put("label_detectado", label == null ? "" : label);
                    moeda.put("centroX", (loc.left + loc.right) * 0.5f);
                    moeda.put("centroY", (loc.top + loc.bottom) * 0.5f);
                } catch (JSONException e) {
                    Log.w(TAG, "Erro ao serializar moeda detectada", e);
                }
                return moeda;
            }
        }
        return null;
    }

    private String encontrarPecaMaisProximaDaMoeda(List<RectF> boxesSnapshot,
                                                    double moedaX,
                                                    double moedaY,
                                                    Map<Integer, String> idsPorIndice) {
        if (Double.isNaN(moedaX) || Double.isNaN(moedaY)) return null;

        int melhorIndice = -1;
        float menorDistancia = Float.MAX_VALUE;
        for (int i = 0; i < boxesSnapshot.size(); i++) {
            RectF box = boxesSnapshot.get(i);
            float cx = (box.left + box.right) * 0.5f;
            float cy = (box.top + box.bottom) * 0.5f;
            float dx = cx - (float) moedaX;
            float dy = cy - (float) moedaY;
            float distancia = (float) Math.sqrt(dx * dx + dy * dy);
            if (distancia < menorDistancia) {
                menorDistancia = distancia;
                melhorIndice = i;
            }
        }
        return melhorIndice >= 0 ? idsPorIndice.get(melhorIndice) : null;
    }


    private JSONArray serializarIndicadores(List<PointF> indexTipsSnapshot) {
        JSONArray indicadores = new JSONArray();
        if (indexTipsSnapshot == null) return indicadores;

        for (PointF point : indexTipsSnapshot) {
            if (point == null) continue;
            JSONObject indicador = new JSONObject();
            try {
                indicador.put("x", point.x);
                indicador.put("y", point.y);
                indicadores.put(indicador);
            } catch (JSONException e) {
                Log.w(TAG, "Erro ao serializar dedo indicador", e);
            }
        }
        return indicadores;
    }

    private String encontrarPecaMaisProximaDoIndicador(List<RectF> boxesSnapshot,
                                                        List<PointF> indexTipsSnapshot,
                                                        Map<Integer, String> idsPorIndice) {
        if (boxesSnapshot == null || boxesSnapshot.isEmpty() || indexTipsSnapshot == null || indexTipsSnapshot.isEmpty()) {
            return null;
        }

        final float proximityFactor = 0.85f;
        int melhorIndice = -1;
        float melhorPontuacao = Float.MAX_VALUE;

        for (PointF tip : indexTipsSnapshot) {
            if (tip == null) continue;

            for (int i = 0; i < boxesSnapshot.size(); i++) {
                RectF box = boxesSnapshot.get(i);
                if (box == null) continue;

                float distanciaBorda = distanciaPontoParaRetangulo(tip, box);
                float escalaPeca = Math.max(1f, Math.max(box.width(), box.height()));
                float distanciaMaxima = escalaPeca * proximityFactor;

                if (distanciaBorda <= distanciaMaxima && distanciaBorda < melhorPontuacao) {
                    melhorPontuacao = distanciaBorda;
                    melhorIndice = i;
                }
            }
        }

        return melhorIndice >= 0 ? idsPorIndice.get(melhorIndice) : null;
    }

    private float distanciaPontoParaRetangulo(PointF ponto, RectF rect) {
        if (ponto == null || rect == null) return Float.MAX_VALUE;
        if (rect.contains(ponto.x, ponto.y)) return 0f;

        float dx = 0f;
        if (ponto.x < rect.left) {
            dx = rect.left - ponto.x;
        } else if (ponto.x > rect.right) {
            dx = ponto.x - rect.right;
        }

        float dy = 0f;
        if (ponto.y < rect.top) {
            dy = rect.top - ponto.y;
        } else if (ponto.y > rect.bottom) {
            dy = ponto.y - rect.bottom;
        }

        return (float) Math.hypot(dx, dy);
    }

    @NonNull
    private String descreverRegiao(RectF box) {
        if (box == null) return "em posição desconhecida";

        float larguraReferencia = (trackingOverlay != null && trackingOverlay.getWidth() > 0)
                ? trackingOverlay.getWidth() : previewWidth;
        float alturaReferencia = (trackingOverlay != null && trackingOverlay.getHeight() > 0)
                ? trackingOverlay.getHeight() : previewHeight;

        float centroX = (box.left + box.right) * 0.5f;
        float centroY = (box.top + box.bottom) * 0.5f;

        float xNormalizado = normalizarCoordenada(centroX, larguraReferencia, box.left, box.right);
        float yNormalizado = normalizarCoordenada(centroY, alturaReferencia, box.top, box.bottom);

        String horizontal;
        if (xNormalizado < 0.33f) horizontal = "esquerda";
        else if (xNormalizado > 0.67f) horizontal = "direita";
        else horizontal = "central";

        String vertical;
        if (yNormalizado < 0.33f) vertical = "superior";
        else if (yNormalizado > 0.67f) vertical = "inferior";
        else vertical = "central";

        if ("central".equals(vertical) && "central".equals(horizontal)) {
            return "no centro do quadro";
        }
        if ("central".equals(vertical)) {
            return "na região central à "
                    + ("esquerda".equals(horizontal) ? "esquerda" : "direita")
                    + " do quadro";
        }
        if ("central".equals(horizontal)) {
            return "na parte " + vertical + " central do quadro";
        }
        return "na parte " + vertical + " " + horizontal + " do quadro";
    }

    private float normalizarCoordenada(float coordenada, float dimensaoReferencia, float limiteA, float limiteB) {
        float maiorLimite = Math.max(limiteA, limiteB);
        float referencia = dimensaoReferencia;

        if (maiorLimite <= 1f && coordenada <= 1f) {
            referencia = 1f;
        }

        if (referencia <= 0f) {
            referencia = Math.max(1f, maiorLimite);
        }

        if (referencia < maiorLimite) {
            referencia = Math.max(1f, maiorLimite);
        }

        float normalizado = referencia <= 0f ? 0f : (coordenada / referencia);
        if (normalizado < 0f) normalizado = 0f;
        if (normalizado > 1f) normalizado = 1f;
        return normalizado;
    }

    private void solicitarConfirmacaoAjudaExterna() {
        if (externalHelpConfirmationDialog != null && externalHelpConfirmationDialog.isShowing()) {
            return;
        }
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        TextView confirmationText = new TextView(this);
        confirmationText.setText(R.string.external_help_confirmation);
        confirmationText.setTextIsSelectable(true);
        confirmationText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        confirmationText.setPadding(padding, padding, padding, padding);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.external_help)
                .setView(confirmationText)
                .setPositiveButton(R.string.yes, (d, which) -> confirmarAjudaExternaPorEscolha(true))
                .setNegativeButton(R.string.no, (d, which) -> confirmarAjudaExternaPorEscolha(false))
                .setOnDismissListener(d -> {
                    listeningForExternalHelpConfirmation = false;
                    pendingExternalHelpConfirmationRequest = false;
                    externalHelpConfirmationDialog = null;
                    pararEscutaVoz();
                })
                .create();
        externalHelpConfirmationDialog = dialog;
        dialog.setOnKeyListener((d, keyCode, event) -> event.getAction() == KeyEvent.ACTION_DOWN
                && keyCode == KeyEvent.KEYCODE_TAB
                && handleDialogTabNavigation(event));
        dialog.show();
        aplicarEstiloBotoesConfirmacaoAjudaExterna(dialog);
        configurarAcessibilidadeDialogo(externalHelpConfirmationDialog);
        falarEmFila(getString(R.string.external_help_voice_prompt), this::iniciarEscutaConfirmacaoAjudaExterna);
    }

    private void confirmarAjudaExternaPorEscolha(boolean confirmar) {
        if (externalHelpConfirmationDialog != null && externalHelpConfirmationDialog.isShowing()) {
            externalHelpConfirmationDialog.dismiss();
        }
        if (confirmar) {
            falarEmFila(getString(R.string.external_help_confirmed));
            abrirAjudaExternaVideochamada();
        } else {
            falarEmFila(getString(R.string.external_help_cancelled));
        }
    }

    private void iniciarEscutaConfirmacaoAjudaExterna() {
        if (speechRecognizer == null || speechRecognizerIntent == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingExternalHelpConfirmationRequest = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_CODE);
            return;
        }
        iniciarEscutaConfirmacaoAjudaExternaComPermissao();
    }

    private void iniciarEscutaConfirmacaoAjudaExternaComPermissao() {
        if (speechRecognizer == null || speechRecognizerIntent == null) return;
        if (externalHelpConfirmationDialog == null || !externalHelpConfirmationDialog.isShowing()) return;
        pararEscutaVoz();
        listeningForExternalHelpConfirmation = true;
        pendingExternalHelpConfirmationRequest = false;
        try {
            abrirMicrofoneAposFimDosAudios(() -> {
                isListeningName = true;
                atualizarIndicadorMicrofone(true);
                speechRecognizer.startListening(speechRecognizerIntent);
                agendarEncerramentoEscuta();
            });
        } catch (Exception e) {
            Log.e(TAG, "Erro ao iniciar confirmação de ajuda externa por voz", e);
            listeningForExternalHelpConfirmation = false;
        }
    }

    private void processarConfirmacaoAjudaExterna(List<String> matches) {
        listeningForExternalHelpConfirmation = false;
        if (matches == null || matches.isEmpty()) {
            repetirPromptConfirmacaoAjudaExterna();
            return;
        }
        VoiceBinaryChoice escolha = interpretarConfirmacaoBinaria(matches);
        if (escolha == VoiceBinaryChoice.YES) {
            confirmarAjudaExternaPorEscolha(true);
            return;
        }
        if (escolha == VoiceBinaryChoice.NO) {
            confirmarAjudaExternaPorEscolha(false);
            return;
        }
        repetirPromptConfirmacaoAjudaExterna();
    }

    private void repetirPromptConfirmacaoAjudaExterna() {
        if (externalHelpConfirmationDialog == null || !externalHelpConfirmationDialog.isShowing()) {
            return;
        }
        falarEmFila(getString(R.string.external_help_voice_retry), this::iniciarEscutaConfirmacaoAjudaExterna);
    }

    private void abrirAjudaExternaVideochamada() {
        Uri roomUri = Uri.parse(JITSI_HELP_ROOM_URL);
        preserveNamedPiecesDuringExternalHelp = true;
        marcarAjudaExternaAtiva();
        entrarEmPipParaManterDeteccao();

        Intent jitsiAppIntent = new Intent(Intent.ACTION_VIEW, roomUri);
        jitsiAppIntent.setPackage(JITSI_MEET_PACKAGE);

        try {
            startActivity(jitsiAppIntent);
            return;
        } catch (ActivityNotFoundException ignored) {
            // Fallback para navegador.
        }

        Intent browserIntent = new Intent(Intent.ACTION_VIEW, roomUri);
        try {
            startActivity(browserIntent);
        } catch (ActivityNotFoundException e) {
            limparEstadoAjudaExternaAtiva();
            mostrarToastCurto(R.string.external_help_unavailable);
        }
    }

    private void marcarAjudaExternaAtiva() {
        getSharedPreferences(APP_NAV_STATE_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(EXTERNAL_HELP_ACTIVE_KEY, true)
                .apply();
    }

    private void limparEstadoAjudaExternaAtiva() {
        getSharedPreferences(APP_NAV_STATE_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(EXTERNAL_HELP_ACTIVE_KEY, false)
                .apply();
    }

    private void entrarEmPipParaManterDeteccao() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || isInPictureInPictureMode()) {
            return;
        }
        int width = (trackingOverlay != null && trackingOverlay.getWidth() > 0) ? trackingOverlay.getWidth() : 16;
        int height = (trackingOverlay != null && trackingOverlay.getHeight() > 0) ? trackingOverlay.getHeight() : 9;
        PictureInPictureParams params = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(width, height))
                .build();
        try {
            enterPictureInPictureMode(params);
        } catch (Exception e) {
            Log.w(TAG, "Não foi possível iniciar Picture-in-Picture.", e);
        }
    }

    private void persistirEstadoDeteccao() {
        JSONArray pieces = new JSONArray();
        synchronized (frozenBoxes) {
            for (int i = 0; i < frozenBoxes.size(); i++) {
                RectF box = frozenBoxes.get(i);
                if (box == null) continue;
                JSONObject piece = new JSONObject();
                try {
                    piece.put("left", box.left);
                    piece.put("top", box.top);
                    piece.put("right", box.right);
                    piece.put("bottom", box.bottom);
                    piece.put("label", i < frozenLabels.size() ? frozenLabels.get(i) : "");
                    piece.put("type", i < frozenTypes.size() ? frozenTypes.get(i) : "");
                    pieces.put(piece);
                } catch (JSONException e) {
                    Log.w(TAG, "Falha ao serializar peça congelada.", e);
                }
            }
        }
        getSharedPreferences(DETECTION_STATE_PREFS, MODE_PRIVATE)
                .edit()
                .putString(DETECTION_STATE_KEY, pieces.toString())
                .apply();
    }

    private void restaurarEstadoDeteccaoPersistido() {
        String serialized = getSharedPreferences(DETECTION_STATE_PREFS, MODE_PRIVATE)
                .getString(DETECTION_STATE_KEY, null);
        if (serialized == null || serialized.isEmpty()) {
            return;
        }
        try {
            JSONArray pieces = new JSONArray(serialized);
            synchronized (frozenBoxes) {
                if (!frozenBoxes.isEmpty() || !frozenLabels.isEmpty()) {
                    return;
                }
                long now = System.currentTimeMillis();
                for (int i = 0; i < pieces.length(); i++) {
                    JSONObject piece = pieces.optJSONObject(i);
                    if (piece == null) continue;
                    RectF box = new RectF(
                            (float) piece.optDouble("left", 0f),
                            (float) piece.optDouble("top", 0f),
                            (float) piece.optDouble("right", 0f),
                            (float) piece.optDouble("bottom", 0f)
                    );
                    if (box.width() <= 0 || box.height() <= 0) continue;
                    frozenBoxes.add(box);
                    frozenLabels.add(piece.optString("label", ""));
                    frozenTypes.add(piece.optString("type", ""));
                    frozenLastSeenMs.add(now);
                    frozenMissingFrames.add(0);
                }
            }
            if (trackingOverlay != null) {
                trackingOverlay.postInvalidate();
            }
        } catch (JSONException e) {
            Log.w(TAG, "Falha ao restaurar estado de detecção.", e);
        }
    }

    @Override
    protected void onPause() {
        freezeWatcherSuspended = true;
        persistirEstadoDeteccao();
        super.onPause();
    }

    @Override
    protected void onStop() {
        persistirEstadoDeteccao();
        super.onStop();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        freezeWatcherSuspended = !isInPictureInPictureMode && !hasWindowFocus();
        if (!isInPictureInPictureMode && hasWindowFocus()) {
            preserveNamedPiecesDuringExternalHelp = false;
        }
    }

    private void solicitarDownloadQuadroAtual() {
        if (detectionPausedForExport) {
            mostrarToastCurto(R.string.operation_in_progress);
            return;
        }
        interromperAudiosParaAcaoDePeca();
        falarImediato(getString(R.string.export_audio_warning));
        if (maoDetectadaRecentemente()) {
            aguardarSaidaMaoParaDownload();
            return;
        }
        pausarDeteccaoParaExportacao(this::salvarQuadroAtualComDeteccoes);
    }

    private boolean maoDetectadaRecentemente() {
        if (lastHandsSeenMs <= 0L) return false;
        long now = System.currentTimeMillis();
        return now - lastHandsSeenMs <= HAND_DOWNLOAD_COOLDOWN_MS;
    }

    private void aguardarSaidaMaoParaDownload() {
        if (handler == null) {
            return;
        }
        handler.postDelayed(() -> {
            if (detectionPausedForExport) {
                return;
            }
            if (maoDetectadaRecentemente()) {
                aguardarSaidaMaoParaDownload();
                return;
            }
            pausarDeteccaoParaExportacao(this::salvarQuadroAtualComDeteccoes);
        }, 100L);
    }

    private void pausarDeteccaoParaExportacao(@NonNull Runnable whenPaused) {
        capturarEstadoParaExportacao();
        detectionPausedForExport = true;
        detectionPaused = true;
        aguardarProcessamentoAtual(whenPaused);
    }

    private void aguardarProcessamentoAtual(@NonNull Runnable whenPaused) {
        if (handler == null) {
            whenPaused.run();
            return;
        }
        if (!isProcessingFrame) {
            whenPaused.run();
        } else {
            handler.postDelayed(() -> aguardarProcessamentoAtual(whenPaused), 16L);
        }
    }

    private void retomarDeteccaoAposExportacao() {
        detectionPausedForExport = false;
        detectionPaused = detectionPausedForContextDescription;
        restaurarEstadoAposExportacao();
    }

    private void retomarDeteccaoAposDescricaoContextual() {
        detectionPausedForContextDescription = false;
        if (!detectionPausedForExport) {
            detectionPaused = false;
        }
        if (handler != null) {
            handler.removeCallbacks(contextDescriptionDetectionPauseWatcher);
        }
    }

    private void abortarExportacaoPorFalha() {
        if (detectionPausedForExport) {
            retomarDeteccaoAposExportacao();
        }
    }

    private void capturarEstadoParaExportacao() {
        exportFrozenBoxes.clear();
        exportFrozenLabels.clear();
        exportFrozenLastSeenMs.clear();
        exportFrozenMissingFrames.clear();
        exportFrozenTypes.clear();
        synchronized (frozenBoxes) {
            for (RectF box : frozenBoxes) {
                exportFrozenBoxes.add(new RectF(box));
            }
            exportFrozenLabels.addAll(frozenLabels);
            exportFrozenLastSeenMs.addAll(frozenLastSeenMs);
            exportFrozenMissingFrames.addAll(frozenMissingFrames);
            exportFrozenTypes.addAll(frozenTypes);
        }
        exportStateCaptured = true;
    }

    private void restaurarEstadoAposExportacao() {
        if (!exportStateCaptured) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (frozenBoxes) {
            frozenBoxes.clear();
            frozenLabels.clear();
            frozenLastSeenMs.clear();
            frozenMissingFrames.clear();
            frozenTypes.clear();
            for (RectF box : exportFrozenBoxes) {
                frozenBoxes.add(new RectF(box));
                frozenLastSeenMs.add(now);
                frozenMissingFrames.add(exportFrozenMissingFrames.size() > frozenMissingFrames.size() ? exportFrozenMissingFrames.get(frozenMissingFrames.size()) : 0);
            }
            frozenLabels.addAll(exportFrozenLabels);
            for (int i = 0; i < exportFrozenTypes.size(); i++) {
                frozenTypes.add(exportFrozenTypes.get(i));
            }
        }
        exportFrozenBoxes.clear();
        exportFrozenLabels.clear();
        exportFrozenLastSeenMs.clear();
        exportFrozenMissingFrames.clear();
        exportFrozenTypes.clear();
        exportStateCaptured = false;
        if (trackingOverlay != null) {
            trackingOverlay.postInvalidate();
        }
    }

    private void salvarQuadroAtualComDeteccoes() {
        Bitmap frameCopy;
        synchronized (latestFrameLock) {
            frameCopy = latestFrameForExport != null
                    ? latestFrameForExport.copy(Bitmap.Config.ARGB_8888, false)
                    : null;
        }

        if (frameCopy == null) {
            mostrarToastCurto(R.string.image_view_not_ready);
            abortarExportacaoPorFalha();
            return;
        }

        if (trackingOverlay == null) {
            mostrarToastCurto(R.string.image_error);
            frameCopy.recycle();
            abortarExportacaoPorFalha();
            return;
        }

        int overlayWidth = trackingOverlay.getWidth();
        int overlayHeight = trackingOverlay.getHeight();

        if (overlayWidth <= 0 || overlayHeight <= 0) {
            mostrarToastCurto(R.string.image_view_not_ready);
            frameCopy.recycle();
            abortarExportacaoPorFalha();
            return;
        }

        Bitmap combinedBitmap = null;
        try {
            Bitmap frameForDisplay = ajustarOrientacaoParaExibicao(frameCopy, overlayWidth, overlayHeight);
            if (frameForDisplay != frameCopy) {
                frameCopy.recycle();
                frameCopy = frameForDisplay;
            }

            combinedBitmap = comporQuadroComMarcacoes(frameCopy, overlayWidth, overlayHeight);

            mostrarDialogoFormato(combinedBitmap);
        } catch (Exception e) {
            Log.e(TAG, "Failed to prepare image for saving", e);
            mostrarToastCurto(R.string.image_error);
            if (combinedBitmap != null && !combinedBitmap.isRecycled()) {
                combinedBitmap.recycle();
            }
            abortarExportacaoPorFalha();
        } finally {
            if (frameCopy != null && !frameCopy.isRecycled()) {
                frameCopy.recycle();
            }
        }
    }

    /**
     * Reproduz exatamente o recorte central usado pelo tracker na tela antes de desenhar as
     * marcações. Manter quadro e overlay no mesmo sistema de coordenadas evita que as caixas sejam
     * esticadas para uma proporção diferente durante a exportação.
     */
    @NonNull
    private Bitmap comporQuadroComMarcacoes(@NonNull Bitmap frame, int outputWidth, int outputHeight) {
        Bitmap result = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);

        float scale = Math.max(
                outputWidth / (float) frame.getWidth(),
                outputHeight / (float) frame.getHeight());
        float scaledWidth = Math.round(frame.getWidth() * scale);
        float scaledHeight = Math.round(frame.getHeight() * scale);
        float left = (outputWidth - scaledWidth) / 2.0f;
        float top = (outputHeight - scaledHeight) / 2.0f;
        RectF frameDestination = new RectF(left, top, left + scaledWidth, top + scaledHeight);

        canvas.drawBitmap(frame, null, frameDestination, null);
        trackingOverlay.draw(canvas);
        return result;
    }

    @NonNull
    private Bitmap ajustarOrientacaoParaExibicao(@NonNull Bitmap bitmap, int overlayWidth, int overlayHeight) {
        int rotationToApply = normalizarRotacao(sensorOrientation);
        if (!orientacaoCompativel(bitmap, rotationToApply, overlayWidth, overlayHeight)) {
            rotationToApply = (rotationToApply + 90) % 360;
            if (!orientacaoCompativel(bitmap, rotationToApply, overlayWidth, overlayHeight)) {
                rotationToApply = (rotationToApply + 180) % 360;
            }
        }

        if (rotationToApply == 0) {
            return bitmap;
        }

        Matrix matrix = new Matrix();
        matrix.postRotate(rotationToApply);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    private boolean orientacaoCompativel(@NonNull Bitmap bitmap, int rotation, int overlayWidth, int overlayHeight) {
        if (overlayWidth <= 0 || overlayHeight <= 0) {
            return true;
        }
        int effectiveRotation = ((rotation % 360) + 360) % 360;
        boolean overlayPortrait = overlayHeight >= overlayWidth;
        boolean rotatedPortrait;
        if (effectiveRotation % 180 == 0) {
            rotatedPortrait = bitmap.getHeight() >= bitmap.getWidth();
        } else {
            rotatedPortrait = bitmap.getWidth() >= bitmap.getHeight();
        }
        return rotatedPortrait == overlayPortrait;
    }

    private int normalizarRotacao(int rotation) {
        int normalized = rotation % 360;
        if (normalized < 0) {
            normalized += 360;
        }
        return normalized;
    }

    private void mostrarDialogoFormato(@NonNull Bitmap combinedBitmap) {
        pendingExportChoiceBitmap = combinedBitmap;
        String[] options = {
                getString(R.string.format_png),
                getString(R.string.format_pdf),
                getString(R.string.format_share)
        };
        exportFormatDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.choose_format_title)
                .setItems(options, (dialog, which) -> {
                    interromperAudiosParaAcaoDePeca();
                    if (which == 0) {
                        aplicarFormatoExportacao(SaveFormat.PNG);
                    } else if (which == 1) {
                        aplicarFormatoExportacao(SaveFormat.PDF);
                    } else if (which == 2) {
                        aplicarCompartilhamentoExportacao();
                    } else {
                        limparSelecaoFormatoExportacao();
                    }
                })
                .setOnCancelListener(dialog -> {
                    interromperAudiosParaAcaoDePeca();
                    limparSelecaoFormatoExportacao();
                    mostrarToastCurto(R.string.save_cancelled);
                })
                .create();
        exportFormatDialog.setOnDismissListener(dialog -> limparSelecaoFormatoExportacao());
        exportFormatDialog.setOnKeyListener((dialog, keyCode, event) -> {
            if (event != null
                    && event.getAction() == KeyEvent.ACTION_DOWN
                    && keyCode == KeyEvent.KEYCODE_TAB) {
                return handleDialogTabNavigation(event);
            }
            return false;
        });
        exportFormatDialog.show();
        aplicarEstiloListaFormatoExportacao(exportFormatDialog);
        configurarAcessibilidadeDialogo(exportFormatDialog);
        falarEmFila(getString(R.string.export_format_voice_prompt), this::iniciarEscutaFormatoExportacao);
    }

    private void aplicarEstiloListaFormatoExportacao(@Nullable AlertDialog dialog) {
        if (dialog == null) return;
        ListView listView = dialog.getListView();
        if (listView == null) return;
        int dividerSize = (int) (4 * getResources().getDisplayMetrics().density);
        listView.setDivider(new ColorDrawable(Color.BLACK));
        listView.setDividerHeight(Math.max(dividerSize, 4));
        listView.post(() -> {
            for (int i = 0; i < listView.getChildCount(); i++) {
                View child = listView.getChildAt(i);
                if (child instanceof TextView) {
                    ((TextView) child).setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                }
            }
        });
    }

    private void aplicarEstiloBotoesConfirmacaoAjudaExterna(@Nullable AlertDialog dialog) {
        if (dialog == null) return;
        boolean isDarkTheme = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int paddingHorizontal = (int) (24 * getResources().getDisplayMetrics().density);
        int paddingVertical = (int) (12 * getResources().getDisplayMetrics().density);
        int strokeWidth = (int) (5 * getResources().getDisplayMetrics().density);
        int buttonBackgroundColor = isDarkTheme ? Color.parseColor("#1E1E1E") : Color.WHITE;
        int simColor = isDarkTheme ? Color.parseColor("#7CFF8A") : Color.parseColor("#1B5E20");
        int naoColor = isDarkTheme ? Color.parseColor("#FF8A80") : Color.parseColor("#B71C1C");

        Button simButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (simButton != null) {
            GradientDrawable simShape = new GradientDrawable();
            simShape.setColor(buttonBackgroundColor);
            simShape.setCornerRadius(12 * getResources().getDisplayMetrics().density);
            simShape.setStroke(strokeWidth, simColor);
            simButton.setBackgroundTintList(null);
            simButton.setBackground(simShape);
            simButton.setTextColor(simColor);
            simButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(18, simButton.getTextSize() / getResources().getDisplayMetrics().scaledDensity));
            simButton.setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical);
            simButton.setMinHeight((int) (56 * getResources().getDisplayMetrics().density));
        }

        Button naoButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (naoButton != null) {
            GradientDrawable naoShape = new GradientDrawable();
            naoShape.setColor(buttonBackgroundColor);
            naoShape.setCornerRadius(12 * getResources().getDisplayMetrics().density);
            naoShape.setStroke(strokeWidth, naoColor);
            naoButton.setBackgroundTintList(null);
            naoButton.setBackground(naoShape);
            naoButton.setTextColor(naoColor);
            naoButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(18, naoButton.getTextSize() / getResources().getDisplayMetrics().scaledDensity));
            naoButton.setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical);
            naoButton.setMinHeight((int) (56 * getResources().getDisplayMetrics().density));
        }

        if (simButton != null && naoButton != null && simButton.getParent() instanceof ViewGroup) {
            ViewGroup buttonContainer = (ViewGroup) simButton.getParent();
            int simIndex = buttonContainer.indexOfChild(simButton);
            int naoIndex = buttonContainer.indexOfChild(naoButton);
            if (simIndex > naoIndex && naoIndex >= 0) {
                buttonContainer.removeView(simButton);
                buttonContainer.addView(simButton, naoIndex);
            }
        }
    }

    private boolean handleDialogTabNavigation(@NonNull KeyEvent event) {
        AlertDialog activeDialog = obterDialogoAcessivelAtivo();
        if (activeDialog == null || !activeDialog.isShowing()) {
            dialogTabNavigationStarted = false;
            return false;
        }
        dialogTabNavigationStarted = true;
        if (isProjectTalkbackEnabled() && isDialogoTipoRelacionamentoAtivo(activeDialog)) {
            cancelarFluxoVozRelacionamentoPorTab();
            if (navegarListaDialogoCircular(activeDialog, event)) {
                return true;
            }
        } else {
            interromperAudiosParaAcaoDePeca();
        }
        List<View> componentes = obterComponentesSelecionaveisDialogo(activeDialog);
        if (componentes.isEmpty()) {
            if (navegarListaDialogoCircular(activeDialog, event)) {
                return true;
            }
            return moverFocoDialogoPeloFocusSearch(activeDialog, event);
        }

        View currentFocus = activeDialog.getCurrentFocus();
        int currentIndex = componentes.indexOf(currentFocus);
        int nextIndex;
        if (currentIndex < 0) {
            nextIndex = event.isShiftPressed() ? componentes.size() - 1 : 0;
        } else if (event.isShiftPressed()) {
            nextIndex = (currentIndex - 1 + componentes.size()) % componentes.size();
        } else {
            nextIndex = (currentIndex + 1) % componentes.size();
        }
        View next = componentes.get(nextIndex);
        if (!next.requestFocus()) {
            return false;
        }
        anunciarComponenteDialogo(next);
        return true;
    }

    private boolean isDialogoTipoRelacionamentoAtivo(@NonNull AlertDialog dialog) {
        if (dialog != currentNameDialog) {
            return false;
        }
        if (pendingNameIndex < 0 || pendingNameIndex >= frozenTypes.size()) {
            return false;
        }
        return "relacionamento".equals(frozenTypes.get(pendingNameIndex));
    }

    private void cancelarFluxoVozRelacionamentoPorTab() {
        pendingRelationshipTypeRequest = false;
        listeningForRelationshipType = false;
        interromperAudiosParaAcaoDePeca();
    }

    private boolean moverFocoDialogoPeloFocusSearch(@NonNull AlertDialog dialog, @NonNull KeyEvent event) {
        View currentFocus = dialog.getCurrentFocus();
        if (currentFocus == null) {
            return false;
        }
        int direction = event.isShiftPressed() ? View.FOCUS_BACKWARD : View.FOCUS_FORWARD;
        View nextFocus = currentFocus.focusSearch(direction);
        if (nextFocus == null || !nextFocus.requestFocus()) {
            return false;
        }
        anunciarComponenteDialogo(nextFocus);
        return true;
    }

    private boolean navegarListaDialogoCircular(@NonNull AlertDialog dialog, @NonNull KeyEvent event) {
        ListView listView = dialog.getListView();
        if (listView == null) {
            return false;
        }
        ListAdapter adapter = listView.getAdapter();
        if (adapter == null || adapter.getCount() == 0) {
            return false;
        }
        int total = adapter.getCount();
        int current = listView.getSelectedItemPosition();
        if (current < 0) {
            View focused = dialog.getCurrentFocus();
            if (focused != null) {
                current = listView.getPositionForView(focused);
            }
        }
        int next;
        if (current < 0) {
            next = event.isShiftPressed() ? total - 1 : 0;
        } else if (event.isShiftPressed()) {
            next = (current - 1 + total) % total;
        } else {
            next = (current + 1) % total;
        }

        listView.setSelection(next);
        listView.smoothScrollToPosition(next);
        final int nextIndex = next;
        listView.post(() -> {
            View child = listView.getChildAt(nextIndex - listView.getFirstVisiblePosition());
            if (child != null) {
                child.requestFocus();
                anunciarComponenteDialogo(child);
                return;
            }
            Object item = adapter.getItem(nextIndex);
            if (item != null) {
                falarImediato(item.toString());
            }
        });
        return true;
    }

    private void configurarAcessibilidadeDialogo(@NonNull AlertDialog dialog) {
        dialogTabNavigationStarted = false;
        View root = dialog.getWindow() != null ? dialog.getWindow().getDecorView() : null;
        if (root == null) {
            return;
        }
        configurarAcessibilidadeRecursiva(root);
    }

    private void configurarAcessibilidadeRecursiva(@NonNull View view) {
        if (isComponenteSelecionavelDialogo(view)) {
            view.setFocusable(true);
            view.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus && dialogTabNavigationStarted) {
                    anunciarComponenteDialogo(v);
                }
            });
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                configurarAcessibilidadeRecursiva(group.getChildAt(i));
            }
        }
    }

    @NonNull
    private List<View> obterComponentesSelecionaveisDialogo(@NonNull AlertDialog dialog) {
        List<View> componentes = new ArrayList<>();
        View root = dialog.getWindow() != null ? dialog.getWindow().getDecorView() : null;
        if (root == null) {
            return componentes;
        }
        coletarComponentesSelecionaveis(root, componentes);
        return componentes;
    }

    private void coletarComponentesSelecionaveis(@NonNull View view, @NonNull List<View> componentes) {
        if (isComponenteSelecionavelDialogo(view)) {
            componentes.add(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                coletarComponentesSelecionaveis(group.getChildAt(i), componentes);
            }
        }
    }

    private boolean isComponenteSelecionavelDialogo(@NonNull View view) {
        if (!view.isShown() || !view.isEnabled()) {
            return false;
        }
        if (view instanceof Button || view instanceof EditText) {
            return true;
        }
        return view instanceof TextView && view.isClickable();
    }

    private void anunciarComponenteDialogo(@NonNull View view) {
        String anuncio = obterAnuncioComponenteDialogo(view);
        if (TextUtils.isEmpty(anuncio)) {
            return;
        }
        interromperAudiosParaAcaoDePeca();
        falarImediato(anuncio);
    }

    @NonNull
    private String obterAnuncioComponenteDialogo(@NonNull View view) {
        CharSequence descricao = view.getContentDescription();
        if (!TextUtils.isEmpty(descricao)) {
            return descricao.toString();
        }
        if (view instanceof EditText) {
            EditText input = (EditText) view;
            CharSequence hint = input.getHint();
            if (!TextUtils.isEmpty(hint)) {
                return hint + ", campo de inserir texto";
            }
            CharSequence texto = input.getText();
            if (!TextUtils.isEmpty(texto)) {
                return texto + ", campo de inserir texto";
            }
            return "Campo de inserir texto";
        }
        if (view instanceof TextView) {
            CharSequence texto = ((TextView) view).getText();
            if (!TextUtils.isEmpty(texto)) {
                return texto.toString();
            }
        }
        return "";
    }

    @Nullable
    private AlertDialog obterDialogoAcessivelAtivo() {
        if (currentNameDialog != null && currentNameDialog.isShowing()) {
            return currentNameDialog;
        }
        if (removalDecisionDialog != null && removalDecisionDialog.isShowing()) {
            return removalDecisionDialog;
        }
        if (externalHelpConfirmationDialog != null && externalHelpConfirmationDialog.isShowing()) {
            return externalHelpConfirmationDialog;
        }
        if (exportFormatDialog != null && exportFormatDialog.isShowing()) {
            return exportFormatDialog;
        }
        if (contextDescriptionManager != null) {
            AlertDialog dialogoDescricao = contextDescriptionManager.getDescriptionDialogIfShowing();
            if (dialogoDescricao != null && dialogoDescricao.isShowing()) {
                return dialogoDescricao;
            }
        }
        return null;
    }

    private boolean isAcaoComDialogoBloqueandoNovaDeteccao() {
        if (externalHelpConfirmationDialog != null && externalHelpConfirmationDialog.isShowing()) {
            return true;
        }
        if (exportFormatDialog != null && exportFormatDialog.isShowing()) {
            return true;
        }
        if (contextDescriptionManager == null) {
            return false;
        }
        AlertDialog dialogoDescricao = contextDescriptionManager.getDescriptionDialogIfShowing();
        return dialogoDescricao != null && dialogoDescricao.isShowing();
    }

    private void aplicarFormatoExportacao(@NonNull SaveFormat format) {
        Bitmap bitmap = pendingExportChoiceBitmap;
        pendingExportChoiceBitmap = null;
        if (exportFormatDialog != null) {
            exportFormatDialog.setOnDismissListener(null);
            exportFormatDialog.dismiss();
            exportFormatDialog = null;
        }
        if (bitmap == null) {
            abortarExportacaoPorFalha();
            return;
        }
        prepararSelecaoLocalSalvamento(bitmap, format);
    }

    private void aplicarCompartilhamentoExportacao() {
        Bitmap bitmap = pendingExportChoiceBitmap;
        pendingExportChoiceBitmap = null;
        if (exportFormatDialog != null) {
            exportFormatDialog.setOnDismissListener(null);
            exportFormatDialog.dismiss();
            exportFormatDialog = null;
        }
        if (bitmap == null) {
            abortarExportacaoPorFalha();
            return;
        }
        compartilharImagem(bitmap);
    }

    private void limparSelecaoFormatoExportacao() {
        if (listeningForExportFormat && speechRecognizer != null) {
            speechRecognizer.cancel();
        }
        listeningForExportFormat = false;
        pendingExportFormatRequest = false;
        isListeningName = false;
        if (pendingExportChoiceBitmap != null) {
            pendingExportChoiceBitmap.recycle();
            pendingExportChoiceBitmap = null;
            abortarExportacaoPorFalha();
        }
        exportFormatDialog = null;
    }

    private void prepararSelecaoLocalSalvamento(@NonNull Bitmap combinedBitmap, @NonNull SaveFormat format) {
        liberarBitmapPendente();
        pendingCombinedBitmap = combinedBitmap;
        pendingSaveFormat = format;

        String suggestedName = "detector_" + System.currentTimeMillis() +
                (format == SaveFormat.PNG ? ".png" : ".pdf");
        String mimeType = (format == SaveFormat.PNG) ? "image/png" : "application/pdf";

        iniciarSelecaoLocalSalvamento(suggestedName, mimeType);
    }

    private void iniciarSelecaoLocalSalvamento(@NonNull String suggestedName, @NonNull String mimeType) {
        if (createDocumentLauncher == null) {
            Log.w(TAG, "Create document launcher not initialized");
            mostrarToastCurto(R.string.image_error);
            liberarBitmapPendente();
            abortarExportacaoPorFalha();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_TITLE, suggestedName);
        createDocumentLauncher.launch(intent);
    }

    private void onCreateDocumentResult(ActivityResult result) {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            Uri uri = result.getData().getData();
            if (uri != null) {
                processarSalvamentoEmUri(uri);
                return;
            } else if (pendingCombinedBitmap != null) {
                mostrarToastCurto(R.string.image_error);
            }
        } else if (pendingCombinedBitmap != null) {
            interromperAudiosParaAcaoDePeca();
            mostrarToastCurto(R.string.save_cancelled);
        }
        liberarBitmapPendente();
        retomarDeteccaoAposExportacao();
    }

    private void processarSalvamentoEmUri(@NonNull Uri uri) {
        if (pendingCombinedBitmap == null || pendingSaveFormat == null) {
            mostrarToastCurto(R.string.image_error);
            liberarBitmapPendente();
            retomarDeteccaoAposExportacao();
            return;
        }

        final Bitmap bitmapToSave = pendingCombinedBitmap;
        final SaveFormat formatToSave = pendingSaveFormat;
        final String structuredDiagramData = montarDadosEstruturadosDoDiagrama();
        mostrarProgressoExportacao();

        new Thread(() -> {
            String altText = contextDescriptionManager != null
                    ? contextDescriptionManager.generateShortAltTextForSave(structuredDiagramData)
                    : getString(R.string.export_alt_text_fallback_generic);
            try {
                if (formatToSave == SaveFormat.PNG) {
                    salvarComoPng(uri, bitmapToSave, altText);
                } else if (formatToSave == SaveFormat.PDF) {
                    salvarComoPdf(uri, bitmapToSave, altText);
                }
                runOnUiThread(() -> {
                    String message = getString(R.string.image_saved, uri.toString());
                    falarEmFila(message);
                });
            } catch (IOException e) {
                Log.e(TAG, "Failed to save exported image", e);
                runOnUiThread(() -> mostrarToastCurto(R.string.image_error));
            } finally {
                runOnUiThread(() -> {
                    esconderProgressoExportacao();
                    liberarBitmapPendente();
                    retomarDeteccaoAposExportacao();
                });
            }
        }, "arpiagrama-export-save").start();
    }

    private void mostrarProgressoExportacao() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (exportProgressDialog == null) {
            exportProgressDialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.export_image)
                    .setMessage(R.string.export_preparing_accessible_file)
                    .setCancelable(false)
                    .create();
        } else {
            exportProgressDialog.setMessage(getString(R.string.export_preparing_accessible_file));
        }
        exportProgressDialog.show();
    }

    private void esconderProgressoExportacao() {
        if (exportProgressDialog != null && exportProgressDialog.isShowing()) {
            exportProgressDialog.dismiss();
        }
    }

    private void salvarComoPng(@NonNull Uri uri, @NonNull Bitmap bitmap, @NonNull String altText) throws IOException {
        File tempFile = File.createTempFile("arpiagrama_export_", ".png", getCacheDir());
        try {
            criarArquivoPngComTextoAlternativo(tempFile, bitmap, altText);
            try (InputStream inputStream = new FileInputStream(tempFile);
                 OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
                if (outputStream == null) {
                    throw new IOException("Unable to open output stream");
                }
                copiarStream(inputStream, outputStream);
                outputStream.flush();
            }
        } finally {
            if (tempFile.exists() && !tempFile.delete()) {
                Log.w(TAG, "Temporary export PNG could not be deleted: " + tempFile.getAbsolutePath());
            }
        }
    }

    private void criarArquivoPngComTextoAlternativo(@NonNull File imageFile,
                                                     @NonNull Bitmap bitmap,
                                                     @NonNull String altText) throws IOException {
        try (FileOutputStream outputStream = new FileOutputStream(imageFile)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)) {
                throw new IOException("Bitmap compression failed");
            }
            outputStream.flush();
        }

        if (TextUtils.isEmpty(altText)) {
            return;
        }

        try {
            ExifInterface exifInterface = new ExifInterface(imageFile.getAbsolutePath());
            exifInterface.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, altText);
            exifInterface.setAttribute(ExifInterface.TAG_USER_COMMENT, altText);
            exifInterface.saveAttributes();
        } catch (IOException e) {
            Log.w(TAG, "Failed to embed alt text in PNG metadata", e);
        }
    }

    private void salvarComoPdf(@NonNull Uri uri, @NonNull Bitmap bitmap, @NonNull String altText) throws IOException {
        PdfDocument pdfDocument = new PdfDocument();
        try (OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
            if (outputStream == null) {
                throw new IOException("Unable to open output stream");
            }

            PdfDocument.PageInfo imagePageInfo = new PdfDocument.PageInfo.Builder(
                    bitmap.getWidth(),
                    bitmap.getHeight(),
                    1
            ).create();
            PdfDocument.Page imagePage = pdfDocument.startPage(imagePageInfo);
            imagePage.getCanvas().drawBitmap(bitmap, 0, 0, null);
            pdfDocument.finishPage(imagePage);

            PdfDocument.PageInfo textPageInfo = new PdfDocument.PageInfo.Builder(
                    Math.max(bitmap.getWidth(), 1080),
                    Math.max(bitmap.getHeight(), 1440),
                    2
            ).create();
            PdfDocument.Page textPage = pdfDocument.startPage(textPageInfo);
            desenharTextoAlternativoNoPdf(textPage.getCanvas(), textPageInfo.getPageWidth(), textPageInfo.getPageHeight(), altText);
            pdfDocument.finishPage(textPage);

            pdfDocument.writeTo(outputStream);
        } finally {
            pdfDocument.close();
        }
    }

    private void desenharTextoAlternativoNoPdf(@NonNull Canvas canvas,
                                                int pageWidth,
                                                int pageHeight,
                                                @NonNull String altText) {
        canvas.drawColor(Color.WHITE);

        float margin = 64f;
        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(Color.BLACK);
        titlePaint.setTextSize(36f);
        titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bodyPaint.setColor(Color.BLACK);
        bodyPaint.setTextSize(28f);

        float y = margin;
        y += -titlePaint.ascent();
        canvas.drawText(getString(R.string.export_alt_text_pdf_title), margin, y, titlePaint);

        float bodyTop = y + 48f;
        List<String> linhas = quebrarLinhasTexto(altText, bodyPaint, pageWidth - (margin * 2f));
        Paint.FontMetrics metrics = bodyPaint.getFontMetrics();
        float lineHeight = (metrics.descent - metrics.ascent) + 16f;
        float currentY = bodyTop - metrics.ascent;
        float maxY = pageHeight - margin;
        for (String linha : linhas) {
            if (currentY > maxY) {
                break;
            }
            canvas.drawText(linha, margin, currentY, bodyPaint);
            currentY += lineHeight;
        }
    }

    @NonNull
    private List<String> quebrarLinhasTexto(@NonNull String text, @NonNull Paint paint, float maxWidth) {
        List<String> linhas = new ArrayList<>();
        if (text.trim().isEmpty()) {
            linhas.add(getString(R.string.export_alt_text_fallback_generic));
            return linhas;
        }

        String[] palavras = text.trim().split("\\s+");
        StringBuilder linhaAtual = new StringBuilder();
        for (String palavra : palavras) {
            String candidata = linhaAtual.length() == 0 ? palavra : linhaAtual + " " + palavra;
            if (paint.measureText(candidata) <= maxWidth || linhaAtual.length() == 0) {
                linhaAtual.setLength(0);
                linhaAtual.append(candidata);
            } else {
                linhas.add(linhaAtual.toString());
                linhaAtual.setLength(0);
                linhaAtual.append(palavra);
            }
        }
        if (linhaAtual.length() > 0) {
            linhas.add(linhaAtual.toString());
        }
        return linhas;
    }

    private void copiarStream(@NonNull InputStream inputStream, @NonNull OutputStream outputStream) throws IOException {
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }
    }

    private void liberarBitmapPendente() {
        if (pendingCombinedBitmap != null && !pendingCombinedBitmap.isRecycled()) {
            pendingCombinedBitmap.recycle();
        }
        pendingCombinedBitmap = null;
        pendingSaveFormat = null;
    }

    private void compartilharImagem(@NonNull Bitmap bitmap) {
        File cacheDir = new File(getCacheDir(), "shared");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            mostrarToastCurto(R.string.image_error);
            if (!bitmap.isRecycled()) {
                bitmap.recycle();
            }
            abortarExportacaoPorFalha();
            return;
        }
        File imageFile = new File(cacheDir, "arpiagrama_share_" + System.currentTimeMillis() + ".png");
        try (FileOutputStream outputStream = new FileOutputStream(imageFile)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)) {
                throw new IOException("Bitmap compression failed");
            }
            outputStream.flush();
        } catch (IOException e) {
            Log.e(TAG, "Failed to share exported image", e);
            mostrarToastCurto(R.string.image_error);
            if (!bitmap.isRecycled()) {
                bitmap.recycle();
            }
            abortarExportacaoPorFalha();
            return;
        }

        if (!bitmap.isRecycled()) {
            bitmap.recycle();
        }

        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", imageFile);
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("image/png");
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        pendingShareReturn = true;
        try {
            startActivity(Intent.createChooser(shareIntent, getString(R.string.export_image)));
        } catch (Exception e) {
            Log.e(TAG, "No activity found to share image", e);
            pendingShareReturn = false;
            mostrarToastCurto(R.string.image_error);
            abortarExportacaoPorFalha();
        }
    }

    private interface CameraSelectorStrategy {
        String select(CameraManager manager) throws CameraAccessException;
    }

    private static final class BackCameraFirstSelector implements CameraSelectorStrategy {
        @Override
        public String select(CameraManager manager) throws CameraAccessException {
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics ch = manager.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id;
                }
            }
            String[] ids = manager.getCameraIdList();
            return ids.length > 0 ? ids[0] : null;
        }
    }

    private static final class CameraFragmentFactory {
        private CameraFragmentFactory() {}

        static CameraConnectionFragment create(CameraConnectionFragment.ConnectionCallback callback,
                                               Activity activity) {
            return CameraConnectionFragment.newInstance(
                    callback,
                    (ImageReader.OnImageAvailableListener) activity,
                    R.layout.camera_fragment,
                    DEFAULT_PREVIEW_SIZE
            );
        }
    }

    private String selecionarCameraPadrao(CameraManager manager) {
        try {
            return cameraSelector.select(manager);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Erro ao selecionar cameraId padrão", e);
            return null;
        }
    }

    protected void configurarFragmento() {
        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        String cameraId = selecionarCameraPadrao(manager);

        CameraConnectionFragment camera2Fragment = CameraFragmentFactory.create(
                new CameraConnectionFragment.ConnectionCallback() {
                    @Override
                    public void onPreviewSizeChosen(final Size size, final int rotation) {
                        previewHeight = size.getHeight();
                        previewWidth = size.getWidth();

                        final float textSizePx = TypedValue.applyDimension(
                                TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP,
                                getResources().getDisplayMetrics());
                        borderedText = new BorderedText(textSizePx);
                        borderedText.setTypeface(Typeface.MONOSPACE);

                        tracker = new MultiBoxTracker(MainActivity.this);

                        int cropSize = TF_OD_API_INPUT_SIZE;
                        sensorOrientation = rotation - obterOrientacaoTela();

                        detectionRoi = calcularRoiMesa(previewWidth, previewHeight);
                        roiPaint.setStyle(Paint.Style.FILL);
                        roiPaint.setColor(Color.GRAY);
                        roiPaint.setAlpha(110);
                        roiPaint.setAntiAlias(true);

                        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888);
                        croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Bitmap.Config.ARGB_8888);

                        frameToCropTransform = ImageUtils.getTransformationMatrix(
                                previewWidth, previewHeight,
                                cropSize, cropSize,
                                sensorOrientation, MAINTAIN_ASPECT);

                        cropToFrameTransform = new Matrix();
                        frameToCropTransform.invert(cropToFrameTransform);

                        trackingOverlay = findViewById(R.id.tracking_overlay);
                        trackingOverlay.addCallback((Canvas canvas) -> {
                            tracker.draw(canvas);
                            desenharRoi(canvas);
                        });

                        tracker.setFrameConfiguration(previewWidth, previewHeight, sensorOrientation);
                    }
                },
                this);

        camera2Fragment.setCamera(cameraId);
        camera2Fragment.setZoomListener(this);
        Fragment fragment = camera2Fragment;
        getSupportFragmentManager().beginTransaction().replace(R.id.container, fragment).commit();
    }

    @Override
    public void onImageAvailable(ImageReader reader) {
        if (previewWidth == 0 || previewHeight == 0) return;
        if (rgbBytes == null) rgbBytes = new int[previewWidth * previewHeight];

        try {
            final Image image = reader.acquireLatestImage();
            if (image == null) return;

            if (detectionPaused) {
                image.close();
                return;
            }

            if (zoomDetectionFrozen) {
                image.close();
                return;
            }

            if (isProcessingFrame) {
                image.close();
                return;
            }
            isProcessingFrame = true;
            final Image.Plane[] planes = image.getPlanes();
            preencherBytes(planes, yuvBytes);
            yRowStride = planes[0].getRowStride();
            final int uvRowStride = planes[1].getRowStride();
            final int uvPixelStride = planes[1].getPixelStride();

            imageConverter = () -> ImageUtils.convertYUV420ToARGB8888(
                    yuvBytes[0], yuvBytes[1], yuvBytes[2],
                    previewWidth, previewHeight,
                    yRowStride, uvRowStride, uvPixelStride, rgbBytes
            );

            postInferenceCallback = () -> {
                image.close();
                isProcessingFrame = false;
            };

            processarImagem();

        } catch (final Exception e) {
            Log.e("MainActivity", "onImageAvailable error", e);
        }
    }

    // ====== A partir daqui, fluxo de detecção/nomeação ======

    public void processarImagem() {

        imageConverter.run();
        if (rgbFrameBitmap == null) {
            if (postInferenceCallback != null) postInferenceCallback.run();
            return;
        }
        emitirMensagemBoasVindas();
        rgbFrameBitmap.setPixels(rgbBytes, 0, previewWidth, 0, 0, previewWidth, previewHeight);
        detectionRoi = calcularRoiMesa(rgbFrameBitmap.getWidth(), rgbFrameBitmap.getHeight());

        Bitmap frameParaDeteccao = rgbFrameBitmap;



        // MediaPipe Hands: detecta mão/pulso e produz também a área de oclusão (mão + antebraço).
        List<HandOcclusion> handDetections = detectarMaosComOclusao(frameParaDeteccao);
        atualizarMaosERegioesOclusao(handDetections);
        List<RectF> handSnapshot = copiarMaos();
        List<RectF> occlusionSnapshot = copiarRegioesOclusao();
        verificarPersistenciaMaos(handSnapshot);




        if (yoloDetector == null) {
            if (postInferenceCallback != null) postInferenceCallback.run();
            return;
        }

        List<YoloV8TfliteDetector.Det> dets = yoloDetector.detect(frameParaDeteccao);

        List<Recognition> recognitions = new ArrayList<>();
        for (YoloV8TfliteDetector.Det d : dets) {
            float confidence = d.score;
            String label = d.label;

            RectF rawBox = d.box;
            if (confidence > MIN_CONFIDENCE && dentroDaRoi(rawBox)) {
                String tipo = determinarTipoPeca(label);
                recognitions.add(new Recognition(label, confidence, "id", rawBox, null, tipo, false, false));
            }
        }
        if (pieceTracker != null) {
            pieceTracker.setFrameSize(previewWidth, previewHeight);
            long trackerNow = System.currentTimeMillis();
            List<PieceTracker.TrackedPiece> trackedPieces = pieceTracker.update(
                    recognitions,
                    trackerNow,
                    (piece, ts) -> emAreaDeOclusao(piece.getEstimatedBox(), occlusionSnapshot),
                    HAND_DOWNLOAD_COOLDOWN_MS
            );
            List<Recognition> trackedRecognitions = new ArrayList<>();
            for (PieceTracker.TrackedPiece trackedPiece : trackedPieces) {
                if (trackedPiece == null || trackedPiece.getEstimatedBox() == null) continue;
                if (trackedPiece.getState() == PieceTracker.TrackState.REMOVIDA) continue;
                if (trackedPiece.isAmbiguous()) continue;
                Recognition tracked = trackedPiece.asRecognition();
                if (tracked == null) continue;
                tracked.setType(determinarTipoPeca(trackedPiece.getClassName()));
                trackedRecognitions.add(tracked);
            }
            recognitions = trackedRecognitions;
        }
        synchronized (latestDetectionsSnapshot) {
            latestDetectionsSnapshot.clear();
            for (Recognition recognition : recognitions) {
                if (recognition == null || recognition.getLocation() == null) continue;
                latestDetectionsSnapshot.add(new Recognition(
                        recognition.getTitle(),
                        recognition.getConfidence(),
                        recognition.getId(),
                        new RectF(recognition.getLocation()),
                        recognition.getName(),
                        recognition.getType(),
                        recognition.isDefined(),
                        recognition.isBeingDefined()
                ));
            }
        }
        long now = System.currentTimeMillis();

        // 1) presença de peças congeladas
        RectF pendingBox = obterCaixaPecaEmDefinicao();
        boolean[] recognitionUsed = new boolean[recognitions.size()];
        for (int i = 0; i < frozenBoxes.size(); i++) {
            RectF f = frozenBoxes.get(i);
            String frozenType = (i < frozenTypes.size()) ? frozenTypes.get(i) : null;
            boolean seen = false;
            boolean blockedByOcclusion = false;
            if (emAreaDeOclusao(f, occlusionSnapshot)) {
                seen = true;
                blockedByOcclusion = true;
            } else {
                for (int rIndex = 0; rIndex < recognitions.size(); rIndex++) {
                    if (recognitionUsed[rIndex]) continue;
                    Recognition r = recognitions.get(rIndex);
                    RectF loc = r.getLocation();
                    if (loc == null) continue;
                    if (!tiposCompativeisParaManterNome(frozenType, r)) continue;
                    if (i != pendingNameIndex && pendingBox != null && sobrepoePecaEmDefinicao(loc, pendingBox, PENDING_PIECE_EXCLUSION_IOU)) {
                        continue;
                    }
                    if (calcularIou(loc, f) >= PRESENCE_IOU_THR
                            || coberturaPorMenorArea(loc, f) >= PRESENCE_MIN_COVERAGE_THR
                            || centroProximoComFracao(f, loc, PRESENCE_CENTER_TOL_FRAC)) {
                        seen = true;
                        recognitionUsed[rIndex] = true;
                        atualizarCaixaCongelada(i, loc);
                        limparReavaliacaoMesmoTipo(i);
                        break;
                    }
                }
            }
            if (!seen) {
                blockedByOcclusion = regiaoOcluidaPorObjeto(f, recognitions, occlusionSnapshot, pendingBox, i);
            }
            if (!seen && i == pendingNameIndex && frozenType != null) {
                int melhorIndicePendente = encontrarMelhorReconhecimentoPendente(recognitions, frozenType, f, recognitionUsed);
                if (melhorIndicePendente >= 0) {
                    RectF loc = recognitions.get(melhorIndicePendente).getLocation();
                    if (loc != null) {
                        seen = true;
                        recognitionUsed[melhorIndicePendente] = true;
                        atualizarCaixaCongelada(i, loc);
                        limparReavaliacaoMesmoTipo(i);
                    }
                }
            }
            if (!seen && frozenType != null) {
                int melhorIndice = encontrarMelhorReconhecimentoPorTipo(recognitions, frozenType, f, recognitionUsed, pendingBox);
                if (melhorIndice >= 0) {
                    RectF loc = recognitions.get(melhorIndice).getLocation();
                    if (loc != null && !sobrepoeAlgumCongelado(loc, 0.2f, i)) {
                        recognitionUsed[melhorIndice] = true;
                        if (!occlusionSnapshot.isEmpty() && !centroProximoComFracao(f, loc, PRESENCE_CENTER_TOL_FRAC)
                                && !confirmarReavaliacaoMesmoTipo(i, loc, now)) {
                            seen = true;
                        } else {
                            seen = true;
                            atualizarCaixaCongelada(i, loc);
                            limparReavaliacaoMesmoTipo(i);
                        }
                    }
                }
            }
            if (!seen) {
                limparReavaliacaoMesmoTipo(i);
            }
            // Se há oclusão, bloqueamos remoção e redefinição por ausência temporária.
            if (seen || blockedByOcclusion) {
                if (i < frozenLastSeenMs.size()) frozenLastSeenMs.set(i, now);
                else frozenLastSeenMs.add(now);
                garantirCapacidadeMissingFrames(i);
                frozenMissingFrames.set(i, 0);
            } else {
                garantirCapacidadeMissingFrames(i);
                frozenMissingFrames.set(i, frozenMissingFrames.get(i) + 1);
            }
        }

        // Estado persistente por peça (tipo, última posição, ausência, nome e estado).
        // O estado OCLUIDA só é aplicado quando a peça está realmente sob a região de oclusão.
        sincronizarEstadosPersistentes(occlusionSnapshot);

        // 2) candidatos novos (sem colidir com congelados)
        List<Recognition> stableCandidates = new ArrayList<>();
        for (Recognition r : recognitions) {
            if (r.getLocation() == null) continue;
            if (emAreaDeOclusao(r.getLocation(), occlusionSnapshot)) continue;
            if (!sobrepoeAlgumCongelado(r.getLocation(), 0.5f, FROZEN_OVERLAP_MIN_COVERAGE_THR)) stableCandidates.add(r);
        }

        if (interactionState == InteractionState.AGUARDANDO_PECA && !isAcaoComDialogoBloqueandoNovaDeteccao()) {
            // 3) reposicionamento (se solicitado)
            tentarReposicionamento(stableCandidates, now);

            // 4) estabilidade (uma peça por vez)
            List<Recognition> namingCandidates = new ArrayList<>();
            for (Recognition candidate : stableCandidates) {
                if (candidate == null) continue;
                String tipo = candidate.getType();
                if (ehClasseNd(tipo) || ehClasseNd(candidate.getTitle())) {
                    continue;
                }
                if (pendingRelocationType != null && tipo != null && tipo.equals(pendingRelocationType)) {
                    continue;
                }
                namingCandidates.add(candidate);
            }

            int detectedCount = namingCandidates.size();
            if (lazySameTypeCheckActive) {
                tentarFinalizarLazySameTypeCheck(namingCandidates, handSnapshot, now);
            }
            if (detectedCount >= 2) {
                if (multiPieceDetectedSinceMs == 0L) {
                    multiPieceDetectedSinceMs = now;
                } else if (now - multiPieceDetectedSinceMs >= MULTI_PIECE_WARN_THRESHOLD_MS) {
                    if (!multiPieceWarningSent || now - multiPieceLastWarningMs >= MULTI_PIECE_REPEAT_INTERVAL_MS) {
                        String msg = "Insira uma peça por vez e aguarde alguns segundos.";
                        interromperAudiosParaAcaoDePeca();
                        falarEmFila(msg);
                        multiPieceWarningSent = true;
                        multiPieceLastWarningMs = now;
                    }
                }
                lastBox = null;
                lastStableLabel = null;
                stableSinceMs = 0L;
            } else {
                multiPieceDetectedSinceMs = 0L;
                multiPieceWarningSent = false;
                multiPieceLastWarningMs = 0L;
                if (detectedCount == 1) {
                    Recognition top = melhorReconhecimento(namingCandidates);
                    if (top != null && top.getLocation() != null) {
                        RectF curr = top.getLocation();
                        // Bloqueia abertura de janela de nova peça enquanto houver mão/pulso
                        // sobre a região candidata (evita redefinição acidental durante oclusão).
                        if (maoSobrepoePecaDefinida(curr, handSnapshot) || emAreaDeOclusao(curr, occlusionSnapshot)) {
                            lastBox = null;
                            lastStableLabel = null;
                            stableSinceMs = 0L;
                        } else {
                            String rawLabel = obterRotuloReconhecimento(top);
                            if (rawLabel == null) rawLabel = "";
                            if (lastBox == null || lastStableLabel == null || !lastStableLabel.equals(rawLabel)) {
                                lastBox = new RectF(curr);
                                lastStableLabel = rawLabel;
                                stableSinceMs = now;
                            } else if (centroProximoSuficiente(lastBox, curr)) {
                                if (stableSinceMs == 0L) stableSinceMs = now;
                                if ((now - stableSinceMs) >= STABILITY_MS && !dialogoNomeAberto()) {
                                    String tipo = top.getType();
                                    boolean iniciouLazyCheck = iniciarLazySameTypeCheck(curr, rawLabel, tipo, occlusionSnapshot);
                                    if (!iniciouLazyCheck) {
                                        interromperAudiosParaAcaoDePeca();
                                        interactionState = InteractionState.PECA_TRAVADA;
                                        iniciarCongelamento(curr, rawLabel);
                                        lastBox = null;
                                        lastStableLabel = null;
                                        stableSinceMs = 0L;
                                    } else {
                                        lastBox = null;
                                        lastStableLabel = null;
                                        stableSinceMs = 0L;
                                        multiPieceDetectedSinceMs = 0L;
                                        multiPieceWarningSent = false;
                                        multiPieceLastWarningMs = 0L;
                                    }
                                }
                            } else {
                                lastBox = new RectF(curr);
                                lastStableLabel = rawLabel;
                                stableSinceMs = now;
                            }
                        }
                    } else {
                        lastBox = null;
                        lastStableLabel = null;
                        stableSinceMs = 0L;
                    }
                } else {
                    lastBox = null;
                    lastStableLabel = null;
                    stableSinceMs = 0L;
                }
            }
        } else {
            lastBox = null;
            lastStableLabel = null;
            stableSinceMs = 0L;
            multiPieceDetectedSinceMs = 0L;
            multiPieceWarningSent = false;
            multiPieceLastWarningMs = 0L;
            resetLazySameTypeCheck();
        }

        // 4) render
        List<Recognition> combined = new ArrayList<>();
        for (int i = 0; i < frozenBoxes.size(); i++) {
            RectF f = frozenBoxes.get(i);
            String lbl = frozenLabels.get(i);
            String disp = (lbl == null || lbl.isEmpty()) ? "Peça" : lbl;
            String tipo = (i < frozenTypes.size()) ? frozenTypes.get(i) : null;
            boolean definido = lbl != null && !lbl.isEmpty();
            boolean sendoDefinido = pendingNameIndex == i;
            combined.add(new Recognition(disp, 1.0f, "frozen", new RectF(f), disp, tipo, definido, sendoDefinido));
        }
        if (interactionState == InteractionState.AGUARDANDO_PECA) {
            List<Recognition> filtered = new ArrayList<>();
            for (Recognition r : recognitions) {
                if (r.getLocation() == null) continue;
                if (emAreaDeOclusao(r.getLocation(), occlusionSnapshot)) continue;
                if (!sobrepoeAlgumCongelado(r.getLocation(), 0.5f, FROZEN_OVERLAP_MIN_COVERAGE_THR)) filtered.add(r);
            }
            combined.addAll(filtered);
        }

        tracker.trackResults(combined, 10);
        trackingOverlay.postInvalidate();

        synchronized (latestFrameLock) {
            if (rgbFrameBitmap != null) {
                if (latestFrameForExport == null
                        || latestFrameForExport.getWidth() != rgbFrameBitmap.getWidth()
                        || latestFrameForExport.getHeight() != rgbFrameBitmap.getHeight()) {
                    latestFrameForExport = Bitmap.createBitmap(
                            rgbFrameBitmap.getWidth(),
                            rgbFrameBitmap.getHeight(),
                            Bitmap.Config.ARGB_8888
                    );
                }
                Canvas copyCanvas = new Canvas(latestFrameForExport);
                copyCanvas.drawBitmap(rgbFrameBitmap, 0, 0, null);
            }
        }

        verificarMudancasAposZoomSeNecessario();

        if (postInferenceCallback != null) postInferenceCallback.run();
    }

    @Override
    public void onZoomStateChanged(boolean zoomActive, float zoomRatio) {
        runOnUiThread(() -> {
            if (zoomActive) {
                ativarModoZoomEstatico();
            } else {
                desativarModoZoomEstatico();
            }
        });
    }

    @Override
    public Bitmap onRequestZoomSnapshot() {
        return criarBitmapComMarcacoesAtuais();
    }

    private void ativarModoZoomEstatico() {
        if (zoomDetectionFrozen) {
            return;
        }
        zoomDetectionFrozen = true;
        pendingZoomStateVerification = false;
        zoomSnapshotBeforeFreeze = capturarZoomSnapshot();
        interromperAudiosParaAcaoDePeca();
        falarImediato("Zoom ativado. Não insira nem retire peças enquanto o zoom estiver ativado.");
    }

    private Bitmap criarBitmapComMarcacoesAtuais() {
        Bitmap frameCopy;
        synchronized (latestFrameLock) {
            frameCopy = latestFrameForExport != null
                    ? latestFrameForExport.copy(Bitmap.Config.ARGB_8888, false)
                    : null;
        }
        if (frameCopy == null || trackingOverlay == null) {
            return null;
        }

        int overlayWidth = trackingOverlay.getWidth();
        int overlayHeight = trackingOverlay.getHeight();
        if (overlayWidth <= 0 || overlayHeight <= 0) {
            frameCopy.recycle();
            return null;
        }

        try {
            Bitmap frameForDisplay = ajustarOrientacaoParaExibicao(frameCopy, overlayWidth, overlayHeight);
            if (frameForDisplay != frameCopy) {
                frameCopy.recycle();
                frameCopy = frameForDisplay;
            }

            return comporQuadroComMarcacoes(frameCopy, overlayWidth, overlayHeight);
        } catch (Exception e) {
            Log.e(TAG, "Falha ao criar snapshot estático do zoom", e);
            return null;
        } finally {
            if (frameCopy != null && !frameCopy.isRecycled()) {
                frameCopy.recycle();
            }
        }
    }

    private void desativarModoZoomEstatico() {
        if (!zoomDetectionFrozen) {
            return;
        }
        zoomDetectionFrozen = false;
        pendingZoomStateVerification = true;
    }

    private ZoomSnapshot capturarZoomSnapshot() {
        synchronized (frozenBoxes) {
            return new ZoomSnapshot(frozenBoxes, frozenLabels, frozenTypes);
        }
    }

    private void verificarMudancasAposZoomSeNecessario() {
        if (!pendingZoomStateVerification || zoomDetectionFrozen) {
            return;
        }
        pendingZoomStateVerification = false;
        ZoomSnapshot snapshot = zoomSnapshotBeforeFreeze;
        zoomSnapshotBeforeFreeze = null;
        interromperAudiosParaAcaoDePeca();
        falarEmFila(getString(R.string.zoom_exit_resume_message));
        if (snapshot == null) {
            return;
        }
        String mensagem = montarMensagemMudancasAposZoom(snapshot, capturarZoomSnapshot());
        if (mensagem != null) {
            falarEmFila(mensagem);
        }
    }

    private String montarMensagemMudancasAposZoom(ZoomSnapshot anterior, ZoomSnapshot atual) {
        int removidas = contarPecasRemovidas(anterior, atual);
        int adicionadas = contarPecasRemovidas(atual, anterior);
        if (removidas == 0 && adicionadas == 0) {
            return null;
        }
        List<String> partes = new ArrayList<>();
        if (adicionadas > 0) {
            partes.add(adicionadas == 1
                    ? "detectei uma nova marcação após o zoom"
                    : "detectei " + adicionadas + " novas marcações após o zoom");
        }
        if (removidas > 0) {
            partes.add(removidas == 1
                    ? "uma marcação anterior não está mais presente"
                    : removidas + " marcações anteriores não estão mais presentes");
        }
        return "Zoom desativado: " + TextUtils.join(" e ", partes) + ".";
    }

    private int contarPecasRemovidas(ZoomSnapshot origem, ZoomSnapshot destino) {
        if (origem == null) {
            return 0;
        }
        boolean[] matched = new boolean[destino != null ? destino.boxes.size() : 0];
        int faltantes = 0;
        for (int i = 0; i < origem.boxes.size(); i++) {
            RectF origemBox = origem.boxes.get(i);
            String origemLabel = i < origem.labels.size() ? origem.labels.get(i) : null;
            String origemTipo = i < origem.types.size() ? origem.types.get(i) : null;
            int matchIndex = encontrarMarcacaoCorrespondente(origemBox, origemLabel, origemTipo, destino, matched);
            if (matchIndex >= 0) {
                matched[matchIndex] = true;
            } else {
                faltantes++;
            }
        }
        return faltantes;
    }

    private int encontrarMarcacaoCorrespondente(RectF sourceBox,
                                                String sourceLabel,
                                                String sourceType,
                                                ZoomSnapshot target,
                                                boolean[] matched) {
        if (target == null) {
            return -1;
        }
        for (int i = 0; i < target.boxes.size(); i++) {
            if (matched != null && i < matched.length && matched[i]) {
                continue;
            }
            RectF targetBox = target.boxes.get(i);
            String targetLabel = i < target.labels.size() ? target.labels.get(i) : null;
            String targetType = i < target.types.size() ? target.types.get(i) : null;
            if (!mesmoTipoOuRotulo(sourceLabel, sourceType, targetLabel, targetType)) {
                continue;
            }
            if (sourceBox == null || targetBox == null) {
                return i;
            }
            if (calcularIou(sourceBox, targetBox) >= PRESENCE_IOU_THR
                    || coberturaPorMenorArea(sourceBox, targetBox) >= PRESENCE_MIN_COVERAGE_THR
                    || centroProximoComFracao(sourceBox, targetBox, PRESENCE_CENTER_TOL_FRAC)) {
                return i;
            }
        }
        return -1;
    }

    private boolean mesmoTipoOuRotulo(String sourceLabel,
                                      String sourceType,
                                      String targetLabel,
                                      String targetType) {
        String sourceLabelNorm = normalizarRotulo(sourceLabel);
        String sourceTypeNorm = normalizarRotulo(sourceType);
        String targetLabelNorm = normalizarRotulo(targetLabel);
        String targetTypeNorm = normalizarRotulo(targetType);

        if (!sourceLabelNorm.isEmpty() && sourceLabelNorm.equals(targetLabelNorm)) {
            return true;
        }
        return !sourceTypeNorm.isEmpty() && sourceTypeNorm.equals(targetTypeNorm);
    }

    private List<HandOcclusion> detectarMaosComOclusao(Bitmap frame) {
        if (handDetectorHelper == null || frame == null) {
            return Collections.emptyList();
        }
        List<HandOcclusion> maos = handDetectorHelper.detectarMaosComOclusao(frame);
        return maos == null ? Collections.emptyList() : maos;
    }

    private void atualizarMaosERegioesOclusao(List<HandOcclusion> maosComOclusao) {
        synchronized (detectedHandBoxes) {
            detectedHandBoxes.clear();
            detectedIndexTips.clear();
            occlusionRegions.clear();
            if (maosComOclusao == null) {
                return;
            }
            for (HandOcclusion oclusao : maosComOclusao) {
                if (oclusao == null) continue;
                RectF mao = oclusao.getHandBox();
                RectF regiao = oclusao.getOcclusionBox();
                if (mao != null) {
                    detectedHandBoxes.add(new RectF(mao));
                }
                float indexX = oclusao.getIndexTipX();
                float indexY = oclusao.getIndexTipY();
                if (!Float.isNaN(indexX) && !Float.isNaN(indexY)) {
                    detectedIndexTips.add(new PointF(indexX, indexY));
                }
                if (regiao != null) {
                    occlusionRegions.add(new RectF(regiao));
                }
            }

            if (!detectedIndexTips.isEmpty()) {
                cachedIndexTips.clear();
                for (PointF tip : detectedIndexTips) {
                    if (tip != null) {
                        cachedIndexTips.add(new PointF(tip.x, tip.y));
                    }
                }
                lastIndexTipDetectionMs = System.currentTimeMillis();
            }
        }
    }

    private List<RectF> copiarMaos() {
        synchronized (detectedHandBoxes) {
            List<RectF> copia = new ArrayList<>(detectedHandBoxes.size());
            for (RectF mao : detectedHandBoxes) {
                if (mao != null) {
                    copia.add(new RectF(mao));
                }
            }
            return copia;
        }
    }


    private List<PointF> copiarPontosIndicador() {
        synchronized (detectedHandBoxes) {
            List<PointF> origem = detectedIndexTips;
            if (origem.isEmpty()) {
                long now = System.currentTimeMillis();
                if (!cachedIndexTips.isEmpty() && now - lastIndexTipDetectionMs <= INDEX_TIP_PERSISTENCE_MS) {
                    origem = cachedIndexTips;
                }
            }

            List<PointF> copia = new ArrayList<>(origem.size());
            for (PointF ponto : origem) {
                if (ponto != null) {
                    copia.add(new PointF(ponto.x, ponto.y));
                }
            }
            return copia;
        }
    }

    private List<RectF> copiarRegioesOclusao() {
        synchronized (detectedHandBoxes) {
            List<RectF> copia = new ArrayList<>(occlusionRegions.size());
            for (RectF box : occlusionRegions) {
                if (box != null) {
                    copia.add(new RectF(box));
                }
            }
            return copia;
        }
    }

    private void verificarPersistenciaMaos(List<RectF> handSnapshot) {
        long now = System.currentTimeMillis();
        boolean hasHands = handSnapshot != null && !handSnapshot.isEmpty();

        if (hasHands) {
            lastHandsSeenMs = now;
            if (handsSeenSinceMs == 0L) {
                handsSeenSinceMs = now;
                handWarningShown = false;
            } else if (!handWarningShown && now - handsSeenSinceMs >= HAND_PERSISTENCE_MS) {
                handWarningShown = true;
                falarEmFila(HAND_PERSISTENCE_MESSAGE);
            }
        } else {
            handsSeenSinceMs = 0L;
            handWarningShown = false;
        }
    }

    private boolean sobrepoeMao(RectF caixa, List<RectF> maoSnapshot) {
        if (caixa == null || maoSnapshot == null || maoSnapshot.isEmpty()) return false;
        for (RectF mao : maoSnapshot) {
            if (mao == null) continue;
            float interW = Math.max(0f, Math.min(caixa.right, mao.right) - Math.max(caixa.left, mao.left));
            float interH = Math.max(0f, Math.min(caixa.bottom, mao.bottom) - Math.max(caixa.top, mao.top));
            float inter = interW * interH;
            float areaA = Math.max(0f, caixa.width()) * Math.max(0f, caixa.height());
            float areaB = Math.max(0f, mao.width()) * Math.max(0f, mao.height());
            float union = areaA + areaB - inter;
            if (union > 0f && inter / union >= HAND_OVERLAP_IOU_THR) {
                return true;
            }
        }
        return false;
    }

    private boolean maoSobrepoePecaDefinida(RectF caixa, List<RectF> maoSnapshot) {
        if (caixa == null || maoSnapshot == null || maoSnapshot.isEmpty()) return false;

        float centroX = caixa.centerX();
        float centroY = caixa.centerY();
        float areaPeca = Math.max(0f, caixa.width()) * Math.max(0f, caixa.height());

        for (RectF mao : maoSnapshot) {
            if (mao == null) continue;

            if (mao.contains(centroX, centroY)) {
                return true;
            }

            float interW = Math.max(0f, Math.min(caixa.right, mao.right) - Math.max(caixa.left, mao.left));
            float interH = Math.max(0f, Math.min(caixa.bottom, mao.bottom) - Math.max(caixa.top, mao.top));
            float inter = interW * interH;
            if (inter <= 0f) continue;

            float areaMao = Math.max(0f, mao.width()) * Math.max(0f, mao.height());
            float union = areaPeca + areaMao - inter;
            float iou = union > 0f ? inter / union : 0f;
            float coberturaPeca = areaPeca > 0f ? inter / areaPeca : 0f;

            if (iou >= 0.05f || coberturaPeca >= 0.10f) {
                return true;
            }
        }
        return false;
    }

    private boolean emAreaDeOclusao(RectF caixa, List<RectF> oclusoes) {
        if (caixa == null || oclusoes == null || oclusoes.isEmpty()) return false;
        for (RectF area : oclusoes) {
            if (area == null) continue;
            if (calcularIou(caixa, area) >= OCCLUSION_OVERLAP_IOU_THR
                    || coberturaPorMenorArea(caixa, area) >= OCCLUSION_OVERLAP_IOU_THR
                    || centroProximoComFracao(caixa, area, OCCLUSION_CENTER_TOL_FRAC)) {
                return true;
            }
        }
        return false;
    }

    private void interromperAudiosParaAcaoDePeca() {
        if (speechController != null) {
            speechController.stopAll();
        }
        pararEscutaVoz();
    }

    private void mostrarToastCurto(@StringRes int resId) {
        falarEmFila(getString(resId));
    }

    private void mostrarFalhaReconhecimentoVoz() {
        mostrarToastCurto(R.string.voice_start_error);
    }

    private String formatarTipoPeca(String tipo) {
        if (tipo == null || tipo.trim().isEmpty()) {
            return "peça";
        }
        return tipo.trim();
    }

    private String mensagemPecaRemovida(String tipo) {
        String tipoFormatado = formatarTipoPeca(tipo);
        if ("peça".equals(tipoFormatado)) {
            return "Peça removida do quadro.";
        }
        return "Peça " + tipoFormatado + " removida do quadro.";
    }

    private void falarImediato(String texto) {
        if (speechController != null) {
            speechController.speakImmediate(texto);
        }
    }
    private void falarEmFila(String texto, Runnable onDone) {
        if (speechController != null) {
            speechController.speakQueued(texto, onDone);
        } else if (onDone != null) {
            onDone.run();
        }
    }
    private void falarEmFila(String texto) {
        if (speechController != null) {
            speechController.speakQueued(texto);
        }
    }

    private void abrirMicrofoneAposFimDosAudios(Runnable abrirMicrofoneAction) {
        if (abrirMicrofoneAction == null) return;
        if (speechController != null) {
            speechController.runAfterQueue(abrirMicrofoneAction);
        } else {
            abrirMicrofoneAction.run();
        }
    }

    private void configurarReconhecimentoVoz() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "Reconhecimento de voz não está disponível neste dispositivo.");
            return;
        }
        speechRecognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MIN_MIC_LISTEN_WINDOW_MS);

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                isListeningName = true;
                atualizarIndicadorMicrofone(true);
                tocarTomEscuta(true);
            }

            @Override
            public void onBeginningOfSpeech() {}

            @Override
            public void onRmsChanged(float rmsdB) {}

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                isListeningName = false;
                atualizarIndicadorMicrofone(false);
                tocarTomEscuta(false);
            }

            @Override
            public void onError(int error) {
                isListeningName = false;
                atualizarIndicadorMicrofone(false);
                tocarTomEscuta(false);
                cancelNameListeningTimeout();
                if (ignoreNextNameError) {
                    ignoreNextNameError = false;
                    return;
                }
                boolean confirmationAttempt = awaitingNameConfirmation || listeningForNameConfirmation;
                boolean interactiveAttempt = listeningForInteractiveDescription;
                boolean removalAttempt = listeningForRemovalDecision;
                boolean exportAttempt = listeningForExportFormat;
                boolean relationshipAttempt = listeningForRelationshipType;
                boolean externalHelpAttempt = listeningForExternalHelpConfirmation;
                listeningForInteractiveDescription = false;
                pendingInteractiveDescriptionRequest = false;
                listeningForNameConfirmation = false;
                listeningForRemovalDecision = false;
                listeningForExportFormat = false;
                listeningForRelationshipType = false;
                listeningForExternalHelpConfirmation = false;
                if (confirmationAttempt) {
                    return;
                }
                awaitingNameConfirmation = false;
                lastSpokenName = null;
                cancelConfirmationNoResponsePrompt();
                if (pendingVoiceInputTarget != null) {
                    if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        repetirPromptNome();
                        return;
                    }
                    mostrarToastCurto(R.string.voice_listen_error_name);
                    handler.postDelayed(() -> iniciarEscutaNomeComPermissao(pendingVoiceInputTarget), 400);
                } else if (interactiveAttempt) {
                    mostrarToastCurto(R.string.voice_listen_error_command);
                } else if (removalAttempt) {
                    mostrarToastCurto(R.string.voice_listen_error_decision);
                } else if (exportAttempt) {
                    mostrarToastCurto(R.string.voice_listen_error_format);
                    repetirPromptFormatoExportacao();
                } else if (relationshipAttempt) {
                    repetirPromptTipoRelacionamento();
                } else if (externalHelpAttempt) {
                    repetirPromptConfirmacaoAjudaExterna();
                }
            }

            @Override
            public void onResults(Bundle results) {
                isListeningName = false;
                atualizarIndicadorMicrofone(false);
                cancelNameListeningTimeout();
                List<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    if (listeningForExportFormat) {
                        processarFormatoExportacao(matches);
                    } else if (listeningForNameConfirmation) {
                        processarConfirmacaoNome(matches);
                    } else if (listeningForRemovalDecision) {
                        processarDecisaoRemocao(matches);
                    } else if (listeningForRelationshipType) {
                        processarTipoRelacionamento(matches);
                    } else if (listeningForExternalHelpConfirmation) {
                        processarConfirmacaoAjudaExterna(matches);
                    } else if (pendingVoiceInputTarget != null) {
                        processarNomeFalado(matches);
                    } else if (listeningForInteractiveDescription && comandoDescricaoInterativa(matches)) {
                        solicitarDescricaoContextual();
                    }
                } else if (listeningForExportFormat) {
                    processarFormatoExportacao(matches);
                } else if (listeningForRelationshipType) {
                    processarTipoRelacionamento(matches);
                } else if (listeningForExternalHelpConfirmation) {
                    processarConfirmacaoAjudaExterna(matches);
                }
                if (!awaitingNameConfirmation) {
                    pendingVoiceInputTarget = null;
                }
                listeningForInteractiveDescription = false;
                pendingInteractiveDescriptionRequest = false;
            }

            @Override
            public void onPartialResults(Bundle partialResults) {}

            @Override
            public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void iniciarNomePorVoz(EditText input) {
        if (input == null) return;
        pendingInteractiveDescriptionRequest = false;
        resetNameVoiceState();
        if (speechRecognizer == null || speechRecognizerIntent == null) {
            falarEmFila("Reconhecimento de voz não está disponível.");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingVoiceInputTarget = input;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_CODE);
            return;
        }
        iniciarEscutaNomeComPermissao(input, true);
    }

    private void processarNomeFalado(List<String> matches) {
        if (pendingVoiceInputTarget == null || matches == null || matches.isEmpty()) return;
        String spoken = matches.get(0);
        pendingVoiceInputTarget.setText(spoken);
        pendingVoiceInputTarget.setSelection(spoken.length());
        lastSpokenName = spoken;
        awaitingNameConfirmation = true;
        falarEmFila(getString(R.string.voice_confirmation_prompt, spoken), this::iniciarEscutaConfirmacaoNome);
    }

    private void processarConfirmacaoNome(List<String> matches) {
        listeningForNameConfirmation = false;
        if (matches == null || matches.isEmpty()) {
            aplicarNomeConfirmado();
            return;
        }
        cancelConfirmationNoResponsePrompt();
        EditText targetInput = pendingVoiceInputTarget;
        VoiceBinaryChoice escolha = interpretarConfirmacaoBinaria(matches);
        if (escolha == VoiceBinaryChoice.YES) {
            aplicarNomeConfirmado();
        } else if (escolha == VoiceBinaryChoice.REPEAT) {
            awaitingNameConfirmation = false;
            lastSpokenName = null;
            if (targetInput != null) {
                targetInput.setText("");
            }
            falarEmFila(getString(R.string.voice_prompt_retry), () -> iniciarEscutaNomeComPermissao(targetInput));
        } else {
            falarEmFila("Se estiver incorreto, diga repita. Caso contrário, vou confirmar o nome captado.");
            iniciarEscutaConfirmacaoNome();
        }
    }

    private enum VoiceBinaryChoice {
        YES,
        NO,
        REPEAT,
        UNKNOWN
    }

    private VoiceBinaryChoice interpretarConfirmacaoBinaria(List<String> matches) {
        if (matches == null || matches.isEmpty()) {
            return VoiceBinaryChoice.UNKNOWN;
        }
        for (String spoken : matches) {
            if (spoken == null) continue;
            String normalized = Normalizer.normalize(spoken, Normalizer.Form.NFD)
                    .replaceAll("\\p{M}+", "")
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9\\s]", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (normalized.isEmpty()) {
                continue;
            }
            if (ehRespostaPositiva(normalized)) {
                return VoiceBinaryChoice.YES;
            }
            if (ehRespostaNegativa(normalized)) {
                return VoiceBinaryChoice.NO;
            }
            if (ehRespostaRepeticao(normalized)) {
                return VoiceBinaryChoice.REPEAT;
            }
        }
        return VoiceBinaryChoice.UNKNOWN;
    }

    private boolean ehRespostaPositiva(String normalized) {
        return possuiPalavra(normalized, "sim")
                || possuiPalavra(normalized, "confirmo")
                || possuiPalavra(normalized, "positivo")
                || possuiPalavra(normalized, "certo")
                || possuiPalavra(normalized, "isso");
    }

    private boolean ehRespostaRepeticao(String normalized) {
        return possuiPalavra(normalized, "repita")
                || possuiPalavra(normalized, "repetir")
                || possuiPalavra(normalized, "repete")
                || possuiPalavra(normalized, "repeta");
    }

    private boolean ehRespostaNegativa(String normalized) {
        return possuiPalavra(normalized, "nao")
                || possuiPalavra(normalized, "negativo")
                || possuiPalavra(normalized, "nunca")
                || possuiPalavra(normalized, "errado");
    }

    private boolean possuiPalavra(String texto, String palavra) {
        if (texto == null || palavra == null) return false;
        if (texto.equals(palavra)) return true;
        return texto.contains(" " + palavra + " ")
                || texto.startsWith(palavra + " ")
                || texto.endsWith(" " + palavra);
    }

    private void aplicarNomeConfirmado() {
        cancelConfirmationNoResponsePrompt();
        if (pendingVoiceInputTarget == null || lastSpokenName == null) {
            awaitingNameConfirmation = false;
            return;
        }
        String name = lastSpokenName.trim();
        pendingVoiceInputTarget.setText(name);
        pendingVoiceInputTarget.setSelection(name.length());
        awaitingNameConfirmation = false;
        lastSpokenName = null;
        nomeDefinidoPorVoz = true;
        falarEmFila("Nome confirmado: " + name);
        confirmarNomeDialogoAtual();
    }

    private void iniciarEscutaNomeComPermissao(EditText input) {
        iniciarEscutaNomeComPermissao(input, true);
    }

    private void iniciarEscutaNomeComPermissao(EditText input, boolean falarPrompt) {
        if (input == null) return;
        if (speechRecognizer == null || speechRecognizerIntent == null) return;
        pararEscutaVoz();
        pendingVoiceInputTarget = input;
        Runnable iniciarEscuta = () -> {
            if (speechRecognizer == null || speechRecognizerIntent == null) return;
            try {
                isListeningName = true;
                speechRecognizer.startListening(speechRecognizerIntent);
                agendarEncerramentoEscuta();
                agendarTimeoutNome();
            } catch (Exception e) {
                Log.e(TAG, "Erro ao iniciar reconhecimento de voz", e);
                mostrarFalhaReconhecimentoVoz();
                pendingVoiceInputTarget = null;
            }
        };
        try {
            if (falarPrompt && !namePromptSpoken) {
                String prompt = getString(R.string.voice_prompt_name);
                // Sem mensagem em balão para evitar áudio duplicado com TalkBack.
                falarEmFila(prompt, () -> abrirMicrofoneAposFimDosAudios(iniciarEscuta));
                namePromptSpoken = true;
            } else {
                abrirMicrofoneAposFimDosAudios(iniciarEscuta);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao iniciar reconhecimento de voz", e);
            mostrarFalhaReconhecimentoVoz();
            pendingVoiceInputTarget = null;
        }
    }

    private void confirmarNomeDialogoAtual() {
        if (currentNameDialog == null) return;
        Button okButton = currentNameDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (okButton != null) {
            okButton.performClick();
        }
    }

    private void cancelarDialogoDefinicaoPeca() {
        if (currentNameDialog == null || !currentNameDialog.isShowing()) return;
        Button cancelarButton = currentNameDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (cancelarButton != null) {
            cancelarButton.performClick();
            return;
        }
        pararEscutaVoz();
        resetNameVoiceState();
        cancelarCongelamentoSemNomePendente();
        interactionState = InteractionState.AGUARDANDO_PECA;
        currentNameDialog.dismiss();
    }

    private void iniciarEscutaConfirmacaoNome() {
        if (pendingVoiceInputTarget == null) return;
        if (speechRecognizer == null || speechRecognizerIntent == null) return;
        listeningForNameConfirmation = true;
        awaitingNameConfirmation = true;
        try {
            abrirMicrofoneAposFimDosAudios(() -> {
                isListeningName = true;
                speechRecognizer.startListening(speechRecognizerIntent);
                agendarEncerramentoEscuta(NAME_CONFIRMATION_LISTEN_WINDOW_MS);
                agendarChecagemConfirmacaoSemResposta();
            });
        } catch (Exception e) {
            Log.e(TAG, "Erro ao iniciar confirmação do nome", e);
            mostrarFalhaReconhecimentoVoz();
            listeningForNameConfirmation = false;
            awaitingNameConfirmation = false;
        }
    }

    private void iniciarEscutaDescricaoInterativa() {
        if (speechRecognizer == null || speechRecognizerIntent == null) {
            return;
        }
        if (dialogoNomeAberto() || interactionState == InteractionState.DEFININDO_PECA) {
            return;
        }
        if (contextDescriptionManager != null && contextDescriptionManager.isRequestInProgress()) {
            return;
        }
        pendingVoiceInputTarget = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingInteractiveDescriptionRequest = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_CODE);
            return;
        }
        iniciarEscutaDescricaoInterativaComPermissao();
    }

    private void iniciarEscutaDescricaoInterativaComPermissao() {
        if (speechRecognizer == null || speechRecognizerIntent == null) return;
        pararEscutaVoz();
        listeningForInteractiveDescription = true;
        pendingInteractiveDescriptionRequest = false;
        try {
            abrirMicrofoneAposFimDosAudios(() -> {
                isListeningName = true;
                speechRecognizer.startListening(speechRecognizerIntent);
                agendarEncerramentoEscuta();
            });
        } catch (Exception e) {
            Log.e(TAG, "Erro ao iniciar comando de descrição interativa", e);
            listeningForInteractiveDescription = false;
        }
    }

    private void iniciarEscutaFormatoExportacao() {
        if (speechRecognizer == null || speechRecognizerIntent == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingExportFormatRequest = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_CODE);
            return;
        }
        iniciarEscutaFormatoExportacaoComPermissao();
    }

    private void iniciarEscutaFormatoExportacaoComPermissao() {
        if (speechRecognizer == null || speechRecognizerIntent == null) return;
        pararEscutaVoz();
        listeningForExportFormat = true;
        pendingExportFormatRequest = false;
        try {
            abrirMicrofoneAposFimDosAudios(() -> {
                isListeningName = true;
                speechRecognizer.startListening(speechRecognizerIntent);
                agendarEncerramentoEscuta();
            });
        } catch (Exception e) {
            Log.e(TAG, "Erro ao iniciar seleção de formato por voz", e);
            listeningForExportFormat = false;
        }
    }

    private void pararEscutaVoz() {
        if (speechRecognizer != null && isListeningName) {
            speechRecognizer.cancel();
            tocarTomEscuta(false);
        }
        isListeningName = false;
        atualizarIndicadorMicrofone(false);
        listeningForInteractiveDescription = false;
        listeningForNameConfirmation = false;
        listeningForRemovalDecision = false;
        listeningForExportFormat = false;
        listeningForRelationshipType = false;
        listeningForExternalHelpConfirmation = false;
        cancelConfirmationNoResponsePrompt();
        cancelNameListeningTimeout();
        cancelListeningWindowTimeout();
    }


    private void atualizarIndicadorMicrofone(boolean mostrar) {
        if (micIndicatorWindow == null) return;
        micIndicatorWindow.setVisibility(mostrar ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void resetNameVoiceState() {
        cancelConfirmationNoResponsePrompt();
        cancelNameListeningTimeout();
        awaitingNameConfirmation = false;
        listeningForNameConfirmation = false;
        lastSpokenName = null;
        pendingVoiceInputTarget = null;
        namePromptSpoken = false;
        nomeDefinidoPorVoz = false;
        pendingRelationshipTypeRequest = false;
        pendingExternalHelpConfirmationRequest = false;
    }

    private boolean comandoDescricaoInterativa(List<String> matches) {
        if (matches == null || matches.isEmpty()) {
            return false;
        }
        for (String spoken : matches) {
            if (spoken == null) continue;
            String normalized = Normalizer.normalize(spoken, Normalizer.Form.NFD)
                    .replaceAll("\\p{M}+", "")
                    .toLowerCase(Locale.ROOT)
                    .trim();
            if (normalized.contains(INTERACTIVE_DESCRIPTION_COMMAND)) {
                return true;
            }
        }
        return false;
    }

    private void processarFormatoExportacao(List<String> matches) {
        listeningForExportFormat = false;
        if (matches == null || matches.isEmpty()) {
            repetirPromptFormatoExportacao();
            return;
        }
        for (String spoken : matches) {
            if (spoken == null) continue;
            String normalized = Normalizer.normalize(spoken, Normalizer.Form.NFD)
                    .replaceAll("\\p{M}+", "")
                    .toLowerCase(Locale.ROOT)
                    .trim();
            String compact = normalized.replaceAll("[^a-z0-9]", "");
            if (compact.contains("png")) {
                aplicarFormatoExportacao(SaveFormat.PNG);
                return;
            }
            if (compact.contains("pdf")) {
                aplicarFormatoExportacao(SaveFormat.PDF);
                return;
            }
            if (compact.contains("compartilh") || compact.contains("share")) {
                aplicarCompartilhamentoExportacao();
                return;
            }
        }
        repetirPromptFormatoExportacao();
    }

    private void repetirPromptFormatoExportacao() {
        if (exportFormatDialog == null || !exportFormatDialog.isShowing()) {
            return;
        }
        falarEmFila(getString(R.string.export_format_voice_retry), this::iniciarEscutaFormatoExportacao);
    }

    private void agendarChecagemConfirmacaoSemResposta() {
        if (handler == null) return;
        cancelConfirmationNoResponsePrompt();
        confirmationNoResponseRunnable = () -> {
            if (awaitingNameConfirmation) {
                awaitingNameConfirmation = false;
                listeningForNameConfirmation = false;
                confirmationNoResponseRunnable = null;
                aplicarNomeConfirmado();
            }
        };
        handler.postDelayed(confirmationNoResponseRunnable, CONFIRMATION_NO_RESPONSE_DELAY_MS);
    }

    private void cancelConfirmationNoResponsePrompt() {
        if (handler != null && confirmationNoResponseRunnable != null) {
            handler.removeCallbacks(confirmationNoResponseRunnable);
            confirmationNoResponseRunnable = null;
        }
    }

    private void agendarTimeoutNome() {
        if (handler == null) return;
        cancelNameListeningTimeout();
        nameListeningTimeoutRunnable = () -> {
            if (!isListeningName || pendingVoiceInputTarget == null || awaitingNameConfirmation || listeningForNameConfirmation) {
                nameListeningTimeoutRunnable = null;
                return;
            }
            ignoreNextNameError = true;
            pararEscutaVoz();
            repetirPromptNome();
        };
        handler.postDelayed(nameListeningTimeoutRunnable, NAME_LISTEN_WINDOW_MS);
    }

    private void cancelNameListeningTimeout() {
        if (handler != null && nameListeningTimeoutRunnable != null) {
            handler.removeCallbacks(nameListeningTimeoutRunnable);
            nameListeningTimeoutRunnable = null;
        }
    }

    private void agendarEncerramentoEscuta() {
        agendarEncerramentoEscuta(NAME_LISTEN_WINDOW_MS);
    }

    private void agendarEncerramentoEscuta(long listenWindowMs) {
        if (handler == null) return;
        cancelListeningWindowTimeout();
        listeningWindowTimeoutRunnable = () -> {
            if (!isListeningName) {
                listeningWindowTimeoutRunnable = null;
                return;
            }
            if (speechRecognizer != null) {
                speechRecognizer.stopListening();
                tocarTomEscuta(false);
            }
            listeningWindowTimeoutRunnable = null;
        };
        handler.postDelayed(listeningWindowTimeoutRunnable, Math.max(MIN_MIC_LISTEN_WINDOW_MS, listenWindowMs));
    }

    private void cancelListeningWindowTimeout() {
        if (handler != null && listeningWindowTimeoutRunnable != null) {
            handler.removeCallbacks(listeningWindowTimeoutRunnable);
            listeningWindowTimeoutRunnable = null;
        }
    }

    private void repetirPromptNome() {
        if (pendingVoiceInputTarget == null) return;
        if (currentNameDialog == null || !currentNameDialog.isShowing()) return;
        namePromptSpoken = true;
        falarEmFila(getString(R.string.voice_prompt_timeout), () ->
                iniciarEscutaNomeComPermissao(pendingVoiceInputTarget, false));
    }

    private void emitirMensagemBoasVindas() {
        if (welcomeMessageSpoken) {
            return;
        }
        welcomeMessageSpoken = true;
        falarImediato(getString(R.string.welcome_message));
    }

    private void anunciarOrientacaoPosNomeacao() {
        registrarAtividadePeca(true);
        falarEmFila("Peça registrada. Se desejar, continue montando o diagrama.");
        agendarLembreteDescricaoInterativa();
    }

    private void agendarLembreteDescricaoInterativa() {
        nameReminderHandler.removeCallbacks(nameReminderRunnable);
        if (interactionState == InteractionState.DEFININDO_PECA || dialogoNomeAberto()) {
            return;
        }
        if (!lastPieceActivityWasDefinition) {
            return;
        }
        if (totalPecasDefinidas == 0 || totalPecasDefinidas % 3 != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long elapsed = now - lastPieceActivityMs;
        long delay = elapsed >= NAME_REMINDER_DELAY_MS ? 0L : NAME_REMINDER_DELAY_MS - elapsed;
        nameReminderHandler.postDelayed(nameReminderRunnable, delay);
    }

    private boolean dialogoNomeAberto() {
        return (currentNameDialog != null && currentNameDialog.isShowing());
    }

    private void registrarAtividadePeca(boolean definicao) {
        lastPieceActivityMs = System.currentTimeMillis();
        lastPieceActivityWasDefinition = definicao;
        if (definicao) {
            totalPecasDefinidas++;
        }
        if (!definicao) {
            nameReminderHandler.removeCallbacks(nameReminderRunnable);
        }
    }

    private void cancelarAudioAtivo() {
        pararEscutaVoz();
        if (speechController != null) {
            speechController.stopAll();
        }
    }

    private void preencherBytes(final Image.Plane[] planes, final byte[][] yuvBytes) {
        for (int i = 0; i < planes.length; ++i) {
            final ByteBuffer buffer = planes[i].getBuffer();
            if (yuvBytes[i] == null) yuvBytes[i] = new byte[buffer.capacity()];
            buffer.get(yuvBytes[i]);
        }
    }

    protected int obterOrientacaoTela() {
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_270: return 270;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_90:  return 90;
            default: return 0;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (currentNameDialog != null && currentNameDialog.isShowing()) currentNameDialog.dismiss();
        if (removalDecisionDialog != null && removalDecisionDialog.isShowing()) removalDecisionDialog.dismiss();
        if (externalHelpConfirmationDialog != null && externalHelpConfirmationDialog.isShowing()) externalHelpConfirmationDialog.dismiss();
        if (freezeHandler != null) freezeHandler.removeCallbacks(freezeWatcher);
        nameReminderHandler.removeCallbacks(nameReminderRunnable);
        if (handler != null) {
            handler.removeCallbacks(contextDescriptionDetectionPauseWatcher);
        }
        if (contextDescriptionManager != null) {
            contextDescriptionManager.shutdown();
            contextDescriptionManager = null;
        }
        pararEscutaVoz();
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        if (speechController != null) {
            speechController.shutdown();
            speechController = null;
        }
        toneGenerator.release();
        if (handDetectorHelper != null) {
            handDetectorHelper.close();
            handDetectorHelper = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            configurarFragmento();
        } else if (requestCode == AUDIO_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingRemovalDecisionRequest) {
                    iniciarEscutaDecisaoRemocaoComPermissao();
                } else if (pendingInteractiveDescriptionRequest) {
                    iniciarEscutaDescricaoInterativaComPermissao();
                } else if (pendingExportFormatRequest) {
                    iniciarEscutaFormatoExportacaoComPermissao();
                } else if (pendingRelationshipTypeRequest) {
                    iniciarEscutaTipoRelacionamentoComPermissao();
                } else if (pendingExternalHelpConfirmationRequest) {
                    iniciarEscutaConfirmacaoAjudaExternaComPermissao();
                } else {
                    iniciarEscutaNomeComPermissao(pendingVoiceInputTarget);
                }
            } else {
                int toastMessage = R.string.voice_permission_denied_name;
                if (pendingExportFormatRequest) {
                    toastMessage = R.string.voice_permission_denied_format;
                } else if (pendingRemovalDecisionRequest || pendingInteractiveDescriptionRequest || pendingRelationshipTypeRequest || pendingExternalHelpConfirmationRequest) {
                    toastMessage = R.string.voice_permission_denied_command;
                }
                mostrarToastCurto(toastMessage);
                pendingVoiceInputTarget = null;
                pendingInteractiveDescriptionRequest = false;
                pendingRemovalDecisionRequest = false;
                pendingExportFormatRequest = false;
                pendingRelationshipTypeRequest = false;
                pendingExternalHelpConfirmationRequest = false;
            }
        }
    }

    // ====== Diálogo ======
    private void removerPecaCongelada(int index) {
        if (index < 0 || index >= frozenBoxes.size()) return;
        boolean removedPendingPiece = pendingNameIndex == index;
        frozenBoxes.remove(index);
        frozenLabels.remove(index);
        frozenLastSeenMs.remove(index);
        if (index < frozenMissingFrames.size()) {
            frozenMissingFrames.remove(index);
        }
        removerEstadoReavaliacaoMesmoTipo(index);
        if (index < frozenTypes.size()) {
            frozenTypes.remove(index);
        }
        registrarAtividadePeca(false);
        if (index < pieceStates.size()) {
            pieceStates.remove(index);
        }
        if (removedPendingPiece) {
            fecharDialogoNomeSePecaRemovida();
        } else if (pendingNameIndex > index) {
            pendingNameIndex--;
        }
    }

    private void solicitarDecisaoPecaRemovida(String nome, String tipo) {
        if (nome == null || nome.isEmpty()) return;
        if (removalDecisionDialog != null && removalDecisionDialog.isShowing()) {
            return;
        }
        pendingRemovalName = nome;
        pendingRemovalType = tipo;
        runOnUiThread(() -> {
            AlertDialog.Builder b = new AlertDialog.Builder(this).setCancelable(false);
            b.setTitle("Peça removida");
            b.setMessage("A peça " + formatarTipoPeca(tipo) + " \"" + nome + "\" saiu da mesa. Deseja excluir ou alterar a posição?");
            b.setPositiveButton("Excluir", (d, w) -> aplicarDecisaoRemocao(true));
            b.setNegativeButton("Alterar", (d, w) -> aplicarDecisaoRemocao(false));
            removalDecisionDialog = b.create();
            removalDecisionDialog.show();
            falarEmFila("A peça " + formatarTipoPeca(tipo) + " " + nome + " foi removida. Deseja excluir ou alterar a posição?", this::iniciarEscutaDecisaoRemocao);
        });
    }

    private void iniciarEscutaDecisaoRemocao() {
        if (speechRecognizer == null || speechRecognizerIntent == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingRemovalDecisionRequest = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_CODE);
            return;
        }
        iniciarEscutaDecisaoRemocaoComPermissao();
    }

    private void iniciarEscutaDecisaoRemocaoComPermissao() {
        if (speechRecognizer == null || speechRecognizerIntent == null) return;
        pararEscutaVoz();
        listeningForRemovalDecision = true;
        pendingRemovalDecisionRequest = false;
        pendingVoiceInputTarget = null;
        try {
            abrirMicrofoneAposFimDosAudios(() -> {
                isListeningName = true;
                speechRecognizer.startListening(speechRecognizerIntent);
                agendarEncerramentoEscuta();
            });
        } catch (Exception e) {
            Log.e(TAG, "Erro ao iniciar decisão de remoção", e);
            listeningForRemovalDecision = false;
        }
    }

    private void processarDecisaoRemocao(List<String> matches) {
        listeningForRemovalDecision = false;
        if (matches == null || matches.isEmpty()) return;
        for (String spoken : matches) {
            if (spoken == null) continue;
            String normalized = Normalizer.normalize(spoken, Normalizer.Form.NFD)
                    .replaceAll("\\p{M}+", "")
                    .toLowerCase(Locale.ROOT)
                    .trim();
            if (normalized.contains("excluir") || normalized.contains("remover") || normalized.contains("apagar")) {
                aplicarDecisaoRemocao(true);
                return;
            }
            if (normalized.contains("alterar") || normalized.contains("mudar") || normalized.contains("reposicionar")
                    || normalized.contains("posicao") || normalized.contains("posição")) {
                aplicarDecisaoRemocao(false);
                return;
            }
        }
        falarEmFila("Não entendi. Diga excluir ou alterar a posição.", this::iniciarEscutaDecisaoRemocao);
    }

    private void aplicarDecisaoRemocao(boolean excluir) {
        pararEscutaVoz();
        pendingRemovalDecisionRequest = false;
        if (removalDecisionDialog != null && removalDecisionDialog.isShowing()) {
            removalDecisionDialog.dismiss();
        }
        removalDecisionDialog = null;
        if (excluir) {
            falarEmFila("Peça excluída.");
            pendingRemovalName = null;
            pendingRemovalType = null;
            return;
        }
        iniciarReposicionamento(pendingRemovalName, pendingRemovalType);
        pendingRemovalName = null;
        pendingRemovalType = null;
    }

    private void iniciarReposicionamento(String nome, String tipo) {
        pendingRelocationName = nome;
        pendingRelocationType = tipo;
        relocationLastBox = null;
        relocationStableSinceMs = 0L;
        falarEmFila("Reposicione a peça do mesmo tipo por pelo menos três segundos para manter o nome.");
    }

    private boolean tentarReposicionamento(List<Recognition> stableCandidates, long now) {
        if (pendingRelocationType == null || pendingRelocationName == null) {
            relocationLastBox = null;
            relocationStableSinceMs = 0L;
            return false;
        }
        Recognition candidate = melhorReconhecimentoPorTipo(stableCandidates, pendingRelocationType);
        if (candidate == null || candidate.getLocation() == null) {
            relocationLastBox = null;
            relocationStableSinceMs = 0L;
            return false;
        }
        RectF curr = candidate.getLocation();
        if (relocationLastBox == null) {
            relocationLastBox = new RectF(curr);
            relocationStableSinceMs = now;
            return true;
        }
        if (centroProximoSuficiente(relocationLastBox, curr)) {
            if (relocationStableSinceMs == 0L) {
                relocationStableSinceMs = now;
            }
            if ((now - relocationStableSinceMs) >= STABILITY_MS) {
                adicionarPecaReposicionada(curr, pendingRelocationName, pendingRelocationType);
                pendingRelocationName = null;
                pendingRelocationType = null;
                relocationLastBox = null;
                relocationStableSinceMs = 0L;
                return false;
            }
        } else {
            relocationLastBox = new RectF(curr);
            relocationStableSinceMs = now;
        }
        return true;
    }

    private Recognition melhorReconhecimentoPorTipo(List<Recognition> candidates, String tipo) {
        if (candidates == null || candidates.isEmpty() || tipo == null) return null;
        Recognition melhor = null;
        for (Recognition candidate : candidates) {
            if (candidate == null) continue;
            String cTipo = candidate.getType();
            if (cTipo == null || !cTipo.equals(tipo)) continue;
            if (melhor == null || candidate.getConfidence() > melhor.getConfidence()) {
                melhor = candidate;
            }
        }
        return melhor;
    }

    private void adicionarPecaReposicionada(RectF box, String nome, String tipo) {
        if (box == null || nome == null || nome.isEmpty()) return;
        frozenBoxes.add(new RectF(box));
        frozenLabels.add(nome);
        frozenLastSeenMs.add(System.currentTimeMillis());
        frozenMissingFrames.add(0);
        frozenTypes.add(tipo);
        pieceStates.add(new PiecePersistentState(tipo, box));
        registrarAtividadePeca(false);
        falarEmFila("Nome mantido para " + nome + ".");
        if (trackingOverlay != null) {
            trackingOverlay.postInvalidate();
        }
    }

    private void solicitarDialogoNome() {
        if (nameDialogShown) return;
        if (currentNameDialog != null && currentNameDialog.isShowing()) return;
        if (pendingNameIndex < 0 || pendingNameIndex >= frozenBoxes.size()) return;
        nomeDefinidoPorVoz = false;

        String pendingType = pendingNameIndex < frozenTypes.size() ? frozenTypes.get(pendingNameIndex) : null;
        if ("relacionamento".equals(pendingType)) {
            solicitarDialogoRelacionamento();
            return;
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this).setCancelable(false);
        b.setTitle(obterTituloDialogoNomePorTipo(pendingType));

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        enforceFirstLetterUppercase(input);
        input.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN) {
                confirmarNomeDialogoAtual();
                return true;
            }
            return false;
        });

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                interromperAudiosParaAcaoDePeca();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding, padding, padding);

        LinearLayout.LayoutParams fieldLayoutParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        input.setLayoutParams(fieldLayoutParams);

        container.addView(input);

        b.setView(container);

        b.setNegativeButton("OK", (d, w) -> {
            String name = input.getText().toString().trim();
            if (name == null) name = "";
            frozenLabels.set(pendingNameIndex, name);

            interactionState = InteractionState.AGUARDANDO_PECA;
            if (!nomeDefinidoPorVoz && !name.isEmpty()) {
                falarEmFila("Nome digitado: " + name, this::anunciarOrientacaoPosNomeacao);
            } else {
                anunciarOrientacaoPosNomeacao();
            }
            pararEscutaVoz();
            resetNameVoiceState();

            nameDialogShown = true;
            trackingOverlay.postInvalidate();
            currentNameDialog = null;
            pendingNameIndex = -1;

            pendingClassSpoken = null;
            nomeDefinidoPorVoz = false;
        });

        b.setPositiveButton("Cancelar", (d, w) -> {
            falarEmFila("Peça descartada.");
            pararEscutaVoz();
            resetNameVoiceState();
            cancelarCongelamentoSemNomePendente();
            interactionState = InteractionState.AGUARDANDO_PECA;
        });

        currentNameDialog = b.create();
        currentNameDialog.setOnKeyListener((dialog, keyCode, event) -> {
            if (event != null
                    && event.getAction() == KeyEvent.ACTION_DOWN
                    && keyCode == KeyEvent.KEYCODE_TAB) {
                return handleDialogTabNavigation(event);
            }
            if (event != null
                    && event.getAction() == KeyEvent.ACTION_DOWN
                    && keyCode == KeyEvent.KEYCODE_ESCAPE) {
                cancelarDialogoDefinicaoPeca();
                return true;
            }
            return false;
        });
        currentNameDialog.show();
        aplicarEstiloContrasteDialogoDefinicao(currentNameDialog);

        if (pendingClassSpoken != null && !pendingClassSpoken.isEmpty()) {
            boolean vogal = pendingClassSpoken.matches("^[aeiouáéíóúâêîôûãõ].*");
            String artigo = vogal ? "uma" : "um";
            falarEmFila("A peça inserida é " + artigo + " " + pendingClassSpoken + ". Digite o que " + pendingClassSpoken + " representa e clique em OK, ou diga por comando de voz após o sinal.",
                    () -> iniciarEscutaNomeComPermissao(input, false));
        } else {
            iniciarEscutaNomeComPermissao(input, true);
        }
    }

    private void solicitarDialogoRelacionamento() {
        final String[] opcoesRelacionamento = {"Associação", "include", "extend", "Generalização"};

        AlertDialog.Builder b = new AlertDialog.Builder(this).setCancelable(false);
        b.setTitle("Tipo de relacionamento");
        b.setItems(opcoesRelacionamento, (d, which) -> {
            interromperAudiosParaAcaoDePeca();
            pararEscutaVoz();
            resetNameVoiceState();
            String escolha = opcoesRelacionamento[which];
            aplicarTipoRelacionamento(escolha, false);
        });

        b.setPositiveButton("Cancelar", (d, w) -> {
            falarEmFila("Peça descartada.");
            pararEscutaVoz();
            resetNameVoiceState();
            cancelarCongelamentoSemNomePendente();
            interactionState = InteractionState.AGUARDANDO_PECA;
        });

        currentNameDialog = b.create();
        currentNameDialog.setOnKeyListener((dialog, keyCode, event) -> {
            if (event != null
                    && event.getAction() == KeyEvent.ACTION_DOWN
                    && keyCode == KeyEvent.KEYCODE_TAB) {
                return handleDialogTabNavigation(event);
            }
            if (event != null
                    && event.getAction() == KeyEvent.ACTION_DOWN
                    && keyCode == KeyEvent.KEYCODE_ESCAPE) {
                cancelarDialogoDefinicaoPeca();
                return true;
            }
            return false;
        });
        currentNameDialog.show();
        aplicarEstiloContrasteDialogoDefinicao(currentNameDialog);
        falarEmFila("Relacionamento detectado. Escolha uma opção: Associação, include, extend ou Generalização. Você também pode responder por voz após o sinal.",
                this::iniciarEscutaTipoRelacionamento);
    }

    private void aplicarEstiloContrasteDialogoDefinicao(AlertDialog dialog) {
        if (dialog == null) return;

        Button buttonOk = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (buttonOk != null) {
            buttonOk.setBackgroundColor(Color.parseColor("#1B5E20"));
            buttonOk.setTextColor(Color.WHITE);
        }

        Button buttonCancelar = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (buttonCancelar != null) {
            buttonCancelar.setBackgroundColor(Color.parseColor("#B71C1C"));
            buttonCancelar.setTextColor(Color.WHITE);
        }
    }

    private void cancelarCongelamentoSemNomePendente() {
        if (pendingNameIndex >= 0 && pendingNameIndex < frozenBoxes.size()) {
            String lbl = frozenLabels.get(pendingNameIndex);
            if (lbl == null || lbl.isEmpty()) {
                frozenBoxes.remove(pendingNameIndex);
                frozenLabels.remove(pendingNameIndex);
                if (pendingNameIndex < frozenTypes.size()) {
                    frozenTypes.remove(pendingNameIndex);
                }
                if (pendingNameIndex < frozenLastSeenMs.size()) {
                    frozenLastSeenMs.remove(pendingNameIndex);
                }
                if (pendingNameIndex < frozenMissingFrames.size()) {
                    frozenMissingFrames.remove(pendingNameIndex);
                }
            }
        }
        pendingNameIndex = -1;
        pendingClassSpoken = null;
        nameDialogShown = true;
        currentNameDialog = null;
        pararEscutaVoz();
        resetNameVoiceState();
        interactionState = InteractionState.AGUARDANDO_PECA;

        lastBox = null;
        lastStableLabel = null;
        stableSinceMs = 0L;

        if (trackingOverlay != null) trackingOverlay.postInvalidate();

        nameReminderHandler.removeCallbacks(nameReminderRunnable);
    }

    private void fecharDialogoNomeSePecaRemovida() {
        runOnUiThread(() -> {
            if (currentNameDialog != null && currentNameDialog.isShowing()) {
                currentNameDialog.dismiss();
            }
            currentNameDialog = null;
        });

        nameReminderHandler.removeCallbacks(nameReminderRunnable);
        pararEscutaVoz();
        resetNameVoiceState();

        pendingNameIndex = -1;
        pendingClassSpoken = null;
        nameDialogShown = true;
        interactionState = InteractionState.AGUARDANDO_PECA;
    }

    private void iniciarCongelamento(RectF boxToFreeze, String rotuloCru) {
        if (boxToFreeze == null) return;
        frozenBoxes.add(new RectF(boxToFreeze));
        frozenLabels.add("");
        frozenLastSeenMs.add(System.currentTimeMillis());
        frozenMissingFrames.add(0);
        String tipoPeca = determinarTipoPeca(rotuloCru);
        frozenTypes.add(tipoPeca);
        pieceStates.add(new PiecePersistentState(tipoPeca, boxToFreeze));
        pendingClassSpoken = rotuloAmigavel(rotuloCru);
        pendingNameIndex = frozenBoxes.size() - 1;
        nameDialogShown = false;
        currentNameDialog = null;
        nameReminderHandler.removeCallbacks(nameReminderRunnable);
        interactionState = InteractionState.DEFININDO_PECA;
        runOnUiThread(this::solicitarDialogoNome);
    }

    private boolean regiaoOcluidaPorObjeto(RectF caixaCongelada,
                                          List<Recognition> recognitions,
                                          List<RectF> occlusionSnapshot,
                                          RectF pendingBox,
                                          int frozenIndex) {
        if (caixaCongelada == null) {
            return false;
        }
        if (emAreaDeOclusao(caixaCongelada, occlusionSnapshot)) {
            return true;
        }
        if (recognitions == null || recognitions.isEmpty()) {
            return false;
        }
        for (Recognition rec : recognitions) {
            RectF loc = rec.getLocation();
            if (loc == null) continue;
            if (!tiposCompativeisParaManterNome(
                    frozenIndex >= 0 && frozenIndex < frozenTypes.size() ? frozenTypes.get(frozenIndex) : null,
                    rec)) {
                continue;
            }
            if (frozenIndex != pendingNameIndex && pendingBox != null && sobrepoePecaEmDefinicao(loc, pendingBox, PENDING_PIECE_EXCLUSION_IOU)) {
                continue;
            }
            if (calcularIou(loc, caixaCongelada) >= HAND_OVERLAP_IOU_THR
                    || coberturaPorMenorArea(loc, caixaCongelada) >= PRESENCE_MIN_COVERAGE_THR) {
                return true;
            }
        }
        return false;
    }

    private void garantirCapacidadeMissingFrames(int index) {
        while (frozenMissingFrames.size() <= index) {
            frozenMissingFrames.add(0);
        }
    }

    private void sincronizarEstadosPersistentes(List<RectF> occlusionSnapshot) {
        while (pieceStates.size() < frozenBoxes.size()) {
            int idx = pieceStates.size();
            RectF box = idx < frozenBoxes.size() ? frozenBoxes.get(idx) : null;
            String tipo = idx < frozenTypes.size() ? frozenTypes.get(idx) : null;
            pieceStates.add(new PiecePersistentState(tipo, box));
        }
        while (pieceStates.size() > frozenBoxes.size()) {
            pieceStates.remove(pieceStates.size() - 1);
        }
        for (int i = 0; i < pieceStates.size(); i++) {
            PiecePersistentState st = pieceStates.get(i);
            st.tipo = i < frozenTypes.size() ? frozenTypes.get(i) : st.tipo;
            st.nomeDefinido = i < frozenLabels.size() ? frozenLabels.get(i) : st.nomeDefinido;
            st.contadorAusencia = i < frozenMissingFrames.size() ? frozenMissingFrames.get(i) : st.contadorAusencia;
            RectF box = i < frozenBoxes.size() ? frozenBoxes.get(i) : null;
            if (box != null) st.ultimaPosicao = new RectF(box);
            boolean emOclusao = box != null && emAreaDeOclusao(box, occlusionSnapshot);
            if (st.contadorAusencia >= REMOVE_MISSING_FRAME_LIMIT) st.estado = PieceState.REMOVIDA;
            else if (emOclusao) st.estado = PieceState.OCLUIDA;
            else st.estado = PieceState.ATIVA;
        }
    }

    private boolean centroProximoSuficiente(RectF a, RectF b) { return centroProximoComFracao(a, b, CENTER_TOL_FRAC); }
    private boolean centroProximoComFracao(RectF a, RectF b, float frac) {
        if (a == null || b == null) return false;
        float ax = (a.left + a.right) * 0.5f, ay = (a.top + a.bottom) * 0.5f;
        float bx = (b.left + b.right) * 0.5f, by = (b.top + b.bottom) * 0.5f;
        float dx = ax - bx, dy = ay - by;
        float diag = (float) Math.hypot(Math.max(1f, a.width()), Math.max(1f, a.height()));
        float tolPx = Math.max(8f, frac * diag);
        return (dx * dx + dy * dy) <= (tolPx * tolPx);
    }

    private Recognition melhorReconhecimento(List<Recognition> reconhecimentos) {
        if (reconhecimentos == null || reconhecimentos.isEmpty()) return null;
        Recognition melhor = reconhecimentos.get(0);
        for (int i = 1; i < reconhecimentos.size(); i++) {
            if (reconhecimentos.get(i).getConfidence() > melhor.getConfidence()) melhor = reconhecimentos.get(i);
        }
        return melhor;
    }

    private String obterRotuloReconhecimento(Recognition reconhecimento) {
        if (reconhecimento == null) return null;
        try { if (reconhecimento.getTitle() != null) return reconhecimento.getTitle(); } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Method metodo = reconhecimento.getClass().getMethod("getLabel");
            Object retorno = metodo.invoke(reconhecimento);
            if (retorno != null) return String.valueOf(retorno);
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Method metodo = reconhecimento.getClass().getMethod("getDisplayName");
            Object retorno = metodo.invoke(reconhecimento);
            if (retorno != null) return String.valueOf(retorno);
        } catch (Throwable ignored) {}
        return null;
    }

    private float calcularIou(RectF a, RectF b) {
        if (a == null || b == null) return 0f;
        float interW = Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left));
        float interH = Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
        float inter = interW * interH;
        float areaA = Math.max(0, a.width()) * Math.max(0, a.height());
        float areaB = Math.max(0, b.width()) * Math.max(0, b.height());
        float uni = areaA + areaB - inter;
        return uni <= 0 ? 0 : inter / uni;
    }

    private float coberturaPorMenorArea(RectF a, RectF b) {
        if (a == null || b == null) return 0f;
        float interW = Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left));
        float interH = Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
        float inter = interW * interH;
        float areaA = Math.max(0, a.width()) * Math.max(0, a.height());
        float areaB = Math.max(0, b.width()) * Math.max(0, b.height());
        float menorArea = Math.min(areaA, areaB);
        return menorArea <= 0f ? 0f : inter / menorArea;
    }

    private boolean sobrepoeAlgumCongelado(RectF caixa, float limite) {
        return sobrepoeAlgumCongelado(caixa, limite, 0f, -1);
    }

    private boolean sobrepoeAlgumCongelado(RectF caixa, float limite, int ignorarIndex) {
        return sobrepoeAlgumCongelado(caixa, limite, 0f, ignorarIndex);
    }

    private boolean sobrepoeAlgumCongelado(RectF caixa, float limiteIoU, float limiteCoberturaMinArea) {
        return sobrepoeAlgumCongelado(caixa, limiteIoU, limiteCoberturaMinArea, -1);
    }

    private boolean sobrepoeAlgumCongelado(RectF caixa, float limiteIoU, float limiteCoberturaMinArea, int ignorarIndex) {
        if (caixa == null) return false;
        for (int i = 0; i < frozenBoxes.size(); i++) {
            if (i == ignorarIndex) continue;
            RectF congelada = frozenBoxes.get(i);
            if (calcularIou(caixa, congelada) > limiteIoU) return true;
            if (limiteCoberturaMinArea > 0f && coberturaPorMenorArea(caixa, congelada) >= limiteCoberturaMinArea) return true;
        }
        return false;
    }

    private void atualizarCaixaCongelada(int index, RectF nova) {
        if (index < 0 || index >= frozenBoxes.size() || nova == null) return;
        frozenBoxes.set(index, new RectF(nova));
    }

    private boolean confirmarReavaliacaoMesmoTipo(int index, RectF candidateBox, long now) {
        if (candidateBox == null) return false;
        garantirEstadoReavaliacaoMesmoTipo();
        RectF lastCandidate = delayedSameTypeCandidateBoxes.get(index);
        long sinceMs = delayedSameTypeCheckSinceMs.get(index);
        if (lastCandidate == null || sinceMs == 0L || !centroProximoComFracao(lastCandidate, candidateBox, 0.20f)) {
            delayedSameTypeCandidateBoxes.set(index, new RectF(candidateBox));
            delayedSameTypeCheckSinceMs.set(index, now);
            return false;
        }
        if (now - sinceMs < SAME_TYPE_RECHECK_DELAY_MS) {
            return false;
        }
        limparReavaliacaoMesmoTipo(index);
        return true;
    }

    private void garantirEstadoReavaliacaoMesmoTipo() {
        while (delayedSameTypeCheckSinceMs.size() < frozenBoxes.size()) {
            delayedSameTypeCheckSinceMs.add(0L);
            delayedSameTypeCandidateBoxes.add(null);
        }
        while (delayedSameTypeCheckSinceMs.size() > frozenBoxes.size()) {
            int last = delayedSameTypeCheckSinceMs.size() - 1;
            delayedSameTypeCheckSinceMs.remove(last);
            delayedSameTypeCandidateBoxes.remove(last);
        }
    }

    private void limparReavaliacaoMesmoTipo(int index) {
        if (index < 0) return;
        garantirEstadoReavaliacaoMesmoTipo();
        if (index >= delayedSameTypeCheckSinceMs.size()) return;
        delayedSameTypeCheckSinceMs.set(index, 0L);
        delayedSameTypeCandidateBoxes.set(index, null);
    }

    private void resetLazySameTypeCheck() {
        lazySameTypeCheckActive = false;
        lazySameTypeCandidateBox = null;
        lazySameTypeRawLabel = null;
        lazySameTypeType = null;
        lazyNamedIndicesSnapshot.clear();
        lazyNamedBoxesSnapshot.clear();
    }

    private boolean existePecaNomeadaDoMesmoTipo(String tipo) {
        if (tipo == null) return false;
        for (int i = 0; i < frozenBoxes.size(); i++) {
            String nome = i < frozenLabels.size() ? frozenLabels.get(i) : null;
            String tipoCongelado = i < frozenTypes.size() ? frozenTypes.get(i) : null;
            if (nome != null && !nome.isEmpty() && tipo.equals(tipoCongelado)) {
                return true;
            }
        }
        return false;
    }

    private boolean iniciarLazySameTypeCheck(RectF candidateBox, String rawLabel, String tipo, List<RectF> occlusionSnapshot) {
        if (candidateBox == null || tipo == null || !existePecaNomeadaDoMesmoTipo(tipo)) {
            return false;
        }
        if (occlusionSnapshot == null || occlusionSnapshot.isEmpty()) {
            return false;
        }

        lazySameTypeCheckActive = true;
        lazySameTypeCandidateBox = new RectF(candidateBox);
        lazySameTypeRawLabel = rawLabel;
        lazySameTypeType = tipo;
        lazyNamedIndicesSnapshot.clear();
        lazyNamedBoxesSnapshot.clear();

        for (int i = 0; i < frozenBoxes.size(); i++) {
            String nome = i < frozenLabels.size() ? frozenLabels.get(i) : null;
            String tipoCongelado = i < frozenTypes.size() ? frozenTypes.get(i) : null;
            RectF congelada = frozenBoxes.get(i);
            if (nome == null || nome.isEmpty() || congelada == null || !tipo.equals(tipoCongelado)) {
                continue;
            }
            if (emAreaDeOclusao(congelada, occlusionSnapshot)) {
                lazyNamedIndicesSnapshot.add(i);
                lazyNamedBoxesSnapshot.add(new RectF(congelada));
            }
        }

        if (lazyNamedIndicesSnapshot.isEmpty()) {
            resetLazySameTypeCheck();
            return false;
        }

        interromperAudiosParaAcaoDePeca();
        // Enquanto houver oclusão na região, bloqueamos a abertura de nova definição.
        falarEmFila("Tire as mãos da área de detecção por alguns segundos para eu confirmar se há uma nova peça.");
        return true;
    }

    private void tentarFinalizarLazySameTypeCheck(List<Recognition> namingCandidates, List<RectF> handSnapshot, long now) {
        if (!lazySameTypeCheckActive) return;
        if (interactionState != InteractionState.AGUARDANDO_PECA) {
            resetLazySameTypeCheck();
            return;
        }
        if (handSnapshot != null && !handSnapshot.isEmpty()) {
            return;
        }
        if (lazySameTypeCandidateBox == null || lazySameTypeType == null) {
            resetLazySameTypeCheck();
            return;
        }

        for (int i = 0; i < lazyNamedIndicesSnapshot.size(); i++) {
            int frozenIndex = lazyNamedIndicesSnapshot.get(i);
            if (frozenIndex < 0 || frozenIndex >= frozenBoxes.size()) {
                resetLazySameTypeCheck();
                return;
            }
            RectF original = lazyNamedBoxesSnapshot.get(i);
            RectF atual = frozenBoxes.get(frozenIndex);
            if (original == null || atual == null || !centroProximoComFracao(original, atual, PRESENCE_CENTER_TOL_FRAC)) {
                resetLazySameTypeCheck();
                return;
            }
        }

        Recognition sameTypeCandidate = null;
        for (Recognition candidate : namingCandidates) {
            if (candidate == null || candidate.getLocation() == null) continue;
            if (!lazySameTypeType.equals(candidate.getType())) continue;
            if (sobrepoeAlgumCongelado(candidate.getLocation(), 0.5f, FROZEN_OVERLAP_MIN_COVERAGE_THR)) continue;
            if (centroProximoComFracao(lazySameTypeCandidateBox, candidate.getLocation(), 0.25f)
                    || calcularIou(lazySameTypeCandidateBox, candidate.getLocation()) >= 0.35f) {
                sameTypeCandidate = candidate;
                break;
            }
        }

        if (sameTypeCandidate == null) {
            return;
        }

        RectF curr = sameTypeCandidate.getLocation();
        String rawLabel = obterRotuloReconhecimento(sameTypeCandidate);
        if (rawLabel == null || rawLabel.isEmpty()) {
            rawLabel = lazySameTypeRawLabel != null ? lazySameTypeRawLabel : "";
        }

        resetLazySameTypeCheck();
        interromperAudiosParaAcaoDePeca();
        interactionState = InteractionState.PECA_TRAVADA;
        iniciarCongelamento(curr, rawLabel);
        lastBox = null;
        lastStableLabel = null;
        stableSinceMs = 0L;
        multiPieceDetectedSinceMs = 0L;
        multiPieceWarningSent = false;
        multiPieceLastWarningMs = 0L;
    }

    private void removerEstadoReavaliacaoMesmoTipo(int index) {
        if (index < 0 || index >= delayedSameTypeCheckSinceMs.size()) return;
        delayedSameTypeCheckSinceMs.remove(index);
        delayedSameTypeCandidateBoxes.remove(index);
    }

    private int encontrarMelhorReconhecimentoPorTipo(List<Recognition> recognitions, String tipo, RectF referencia, boolean[] usados) {
        return encontrarMelhorReconhecimentoPorTipo(recognitions, tipo, referencia, usados, null);
    }

    private int encontrarMelhorReconhecimentoPorTipo(List<Recognition> recognitions, String tipo, RectF referencia, boolean[] usados, RectF pendingBox) {
        if (recognitions == null || recognitions.isEmpty() || tipo == null) return -1;
        int melhorIndice = -1;
        float menorDistancia = Float.MAX_VALUE;
        float maxDistancia = Float.MAX_VALUE;
        if (previewWidth > 0 && previewHeight > 0) {
            float frac = "relacionamento".equals(tipo) ? RELATIONSHIP_REASSOC_DIST_FRAC : 0.6f;
            maxDistancia = Math.max(previewWidth, previewHeight) * frac;
        }
        for (int i = 0; i < recognitions.size(); i++) {
            if (usados != null && i < usados.length && usados[i]) continue;
            Recognition r = recognitions.get(i);
            if (r == null) continue;
            String rTipo = r.getType();
            if (rTipo == null || !rTipo.equals(tipo)) continue;
            RectF loc = r.getLocation();
            if (loc == null) continue;
            if (pendingBox != null && sobrepoePecaEmDefinicao(loc, pendingBox, PENDING_PIECE_EXCLUSION_IOU)) {
                continue;
            }
            float distancia = distanciaCentros(referencia, loc);
            if (distancia <= maxDistancia && distancia < menorDistancia) {
                menorDistancia = distancia;
                melhorIndice = i;
            }
        }
        return melhorIndice;
    }

    private int encontrarMelhorReconhecimentoPendente(List<Recognition> recognitions, String tipo, RectF referencia, boolean[] usados) {
        if (recognitions == null || recognitions.isEmpty() || tipo == null) return -1;
        int melhorIndice = -1;
        float menorDistancia = Float.MAX_VALUE;
        for (int i = 0; i < recognitions.size(); i++) {
            if (usados != null && i < usados.length && usados[i]) continue;
            Recognition r = recognitions.get(i);
            if (r == null) continue;
            String rTipo = r.getType();
            if (rTipo == null || !rTipo.equals(tipo)) continue;
            RectF loc = r.getLocation();
            if (loc == null) continue;
            float distancia = distanciaCentros(referencia, loc);
            if (distancia < menorDistancia) {
                menorDistancia = distancia;
                melhorIndice = i;
            }
        }
        return melhorIndice;
    }

    private RectF obterCaixaPecaEmDefinicao() {
        if (interactionState != InteractionState.DEFININDO_PECA) return null;
        if (pendingNameIndex < 0 || pendingNameIndex >= frozenBoxes.size()) return null;
        return frozenBoxes.get(pendingNameIndex);
    }

    private boolean sobrepoePecaEmDefinicao(RectF caixa, RectF pendingBox, float limite) {
        if (caixa == null || pendingBox == null) return false;
        return calcularIou(caixa, pendingBox) > limite;
    }

    private float distanciaCentros(RectF a, RectF b) {
        if (a == null || b == null) return Float.MAX_VALUE;
        float ax = (a.left + a.right) / 2f;
        float ay = (a.top + a.bottom) / 2f;
        float bx = (b.left + b.right) / 2f;
        float by = (b.top + b.bottom) / 2f;
        float dx = ax - bx;
        float dy = ay - by;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private String determinarTipoPeca(String rotuloBruto) {
        if (rotuloBruto == null) return null;
        String normalizado = normalizarRotulo(rotuloBruto);
        if (ehClasseNd(normalizado)) return "nd";
        if (normalizado.contains("actor") || normalizado.contains("ator")) return "ator";
        if (normalizado.contains("use case") || normalizado.contains("usecase") || normalizado.contains("caso de uso") || normalizado.contains("usercase")) {
            return "caso de uso";
        }
        if (normalizado.contains("relacionamento") || normalizado.contains("relationship") || normalizado.contains("include") || normalizado.contains("extend")) {
            return "relacionamento";
        }
        return null;
    }

    private boolean tiposCompativeisParaManterNome(String tipoCongelado, Recognition reconhecimento) {
        if (tipoCongelado == null || reconhecimento == null) return true;
        String tipoReconhecido = reconhecimento.getType();
        if (tipoReconhecido == null || tipoReconhecido.isEmpty()) {
            tipoReconhecido = determinarTipoPeca(obterRotuloReconhecimento(reconhecimento));
        }
        if (tipoReconhecido == null || tipoReconhecido.isEmpty()) return true;
        return tipoCongelado.equals(tipoReconhecido);
    }

    private RectF calcularRoiMesa(int width, int height) {
        if (width <= 0 || height <= 0) return null;

        float roiWidth = width * ROI_WIDTH_FRACTION;
        float roiHeight = height * ROI_HEIGHT_FRACTION;
        float left = (width - roiWidth) / 2f;
        float top = (height - roiHeight) / 2f;

        return new RectF(
                left,
                top,
                left + roiWidth,
                top + roiHeight
        );
    }

    private RectF limitarParaRoi(RectF box) {
        RectF roi = detectionRoi;
        if (box == null || roi == null) return box;
        RectF inter = new RectF(
                Math.max(box.left, roi.left),
                Math.max(box.top, roi.top),
                Math.min(box.right, roi.right),
                Math.min(box.bottom, roi.bottom)
        );
        if (inter.left >= inter.right || inter.top >= inter.bottom) {
            return null;
        }
        return inter;
    }

    private void desenharRoi(Canvas canvas) {
        RectF roi = detectionRoi;
        if (roi == null || canvas == null || previewWidth <= 0 || previewHeight <= 0) return;
        Matrix frameToCanvas = ImageUtils.getTransformationMatrix(
                previewWidth,
                previewHeight,
                canvas.getWidth(),
                canvas.getHeight(),
                sensorOrientation,
                false);
        RectF roiTela = new RectF(roi);
        frameToCanvas.mapRect(roiTela);
        Path mascaraForaDaRoi = new Path();
        mascaraForaDaRoi.setFillType(Path.FillType.EVEN_ODD);
        mascaraForaDaRoi.addRect(0f, 0f, canvas.getWidth(), canvas.getHeight(), Path.Direction.CW);
        mascaraForaDaRoi.addRect(roiTela, Path.Direction.CW);
        canvas.drawPath(mascaraForaDaRoi, roiPaint);
    }

    private Bitmap aplicarRecorteRoi(Bitmap frame) {
        RectF roi = detectionRoi;
        if (frame == null || roi == null) return frame;
        if (roiMaskedBitmap == null
                || roiMaskedBitmap.getWidth() != frame.getWidth()
                || roiMaskedBitmap.getHeight() != frame.getHeight()) {
            roiMaskedBitmap = Bitmap.createBitmap(frame.getWidth(), frame.getHeight(), Bitmap.Config.ARGB_8888);
        }
        Canvas canvas = new Canvas(roiMaskedBitmap);
        canvas.drawColor(Color.GRAY);
        Rect src = new Rect(
                Math.max(0, Math.round(roi.left)),
                Math.max(0, Math.round(roi.top)),
                Math.min(frame.getWidth(), Math.round(roi.right)),
                Math.min(frame.getHeight(), Math.round(roi.bottom))
        );
        canvas.drawBitmap(frame, src, roi, null);
        return roiMaskedBitmap;
    }


    private void iniciarEscutaTipoRelacionamento() {
        if (speechRecognizer == null || speechRecognizerIntent == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingRelationshipTypeRequest = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_CODE);
            return;
        }
        iniciarEscutaTipoRelacionamentoComPermissao();
    }

    private void iniciarEscutaTipoRelacionamentoComPermissao() {
        if (speechRecognizer == null || speechRecognizerIntent == null) return;
        pararEscutaVoz();
        listeningForRelationshipType = true;
        pendingRelationshipTypeRequest = false;
        try {
            abrirMicrofoneAposFimDosAudios(() -> {
                isListeningName = true;
                speechRecognizer.startListening(speechRecognizerIntent);
                agendarEncerramentoEscuta();
            });
        } catch (Exception e) {
            Log.e(TAG, "Erro ao iniciar seleção de relacionamento por voz", e);
            listeningForRelationshipType = false;
        }
    }

    private void processarTipoRelacionamento(List<String> matches) {
        listeningForRelationshipType = false;
        if (matches == null || matches.isEmpty()) {
            repetirPromptTipoRelacionamento();
            return;
        }
        for (String spoken : matches) {
            String escolha = mapearRelacionamentoFalado(spoken);
            if (escolha != null) {
                aplicarTipoRelacionamento(escolha, true);
                return;
            }
        }
        repetirPromptTipoRelacionamento();
    }

    private void repetirPromptTipoRelacionamento() {
        if (currentNameDialog == null || !currentNameDialog.isShowing()) return;
        falarEmFila("Não entendi. Diga associação, include, extend ou generalização.", this::iniciarEscutaTipoRelacionamento);
    }

    private String mapearRelacionamentoFalado(String spoken) {
        if (spoken == null) return null;
        String normalized = Normalizer.normalize(spoken, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace('-', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.contains("associ")) return "Associação";
        if (normalized.contains("include")
                || normalized.contains("incluir")
                || normalized.contains("inclui")
                || normalized.contains("incluido")
                || normalized.contains("includ")
                || normalized.contains("incluid")) {
            return "include";
        }
        if (normalized.contains("extend")
                || normalized.contains("extender")
                || normalized.contains("estender")
                || normalized.contains("estende")
                || normalized.contains("extendido")
                || normalized.contains("extende")
                || normalized.contains("extendi")) {
            return "extend";
        }
        if (normalized.contains("generaliz")) return "Generalização";
        return null;
    }

    private String obterTituloDialogoNomePorTipo(String pendingType) {
        if (pendingType == null) return "Nome da peça";
        String normalizado = Normalizer.normalize(pendingType, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .trim();
        if (normalizado.contains("ator") || normalizado.contains("actor")) {
            return "Nome do ator";
        }
        if (normalizado.contains("caso de uso")
                || normalizado.contains("use case")
                || normalizado.contains("usecase")
                || normalizado.contains("usercase")) {
            return "Nome do caso de uso";
        }
        return "Nome da peça";
    }

    private void aplicarTipoRelacionamento(String escolha, boolean definidoPorVoz) {
        if (pendingNameIndex < 0 || pendingNameIndex >= frozenLabels.size()) return;
        frozenLabels.set(pendingNameIndex, escolha);
        interactionState = InteractionState.AGUARDANDO_PECA;
        String mensagem = definidoPorVoz
                ? "Relacionamento definido por voz como " + escolha + "."
                : "Relacionamento definido como " + escolha + ".";
        falarEmFila(mensagem, this::anunciarOrientacaoPosNomeacao);
        pararEscutaVoz();
        resetNameVoiceState();
        nameDialogShown = true;
        if (trackingOverlay != null) trackingOverlay.postInvalidate();
        if (definidoPorVoz && currentNameDialog != null && currentNameDialog.isShowing()) {
            currentNameDialog.dismiss();
        }
        currentNameDialog = null;
        pendingNameIndex = -1;
        pendingClassSpoken = null;
        nomeDefinidoPorVoz = false;
    }

    private boolean dentroDaRoi(RectF box) {
        RectF roi = detectionRoi;
        if (box == null || roi == null) return false;
        return box.left >= roi.left && box.top >= roi.top && box.right <= roi.right && box.bottom <= roi.bottom;
    }

    private void tocarTomEscuta(boolean iniciou) {
        int tone = iniciou ? ToneGenerator.TONE_PROP_BEEP : ToneGenerator.TONE_PROP_BEEP2;
        int durationMs = iniciou ? 120 : 90;
        toneGenerator.startTone(tone, durationMs);
    }

    private String rotuloAmigavel(String rotuloBruto) {
        if (rotuloBruto == null) return "peça";
        String normalizado = normalizarRotulo(rotuloBruto);
        if (ehClasseNd(normalizado)) return "peça";
        if (normalizado.contains("actor") || normalizado.contains("ator")) return "ator";
        if (normalizado.contains("use case") || normalizado.contains("usercase") || normalizado.contains("caso de uso")) return "caso de uso";
        if (normalizado.contains("system") && (normalizado.contains("boundary") || normalizado.contains("fronteira"))) return "fronteira do sistema";
        if (normalizado.contains("package") || normalizado.contains("pacote")) return "pacote";
        if (normalizado.contains("include")) return "relação include";
        if (normalizado.contains("extend"))  return "relação extend";
        return normalizado;
    }

    private boolean ehClasseNd(String rotuloBruto) {
        if (rotuloBruto == null) return false;
        return rotulosNd.contains(normalizarRotulo(rotuloBruto));
    }

    private String normalizarRotulo(String rotuloBruto) {
        if (rotuloBruto == null) return "";
        return rotuloBruto.trim()
                .toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("\\s+", " ");
    }
}
