package com.example.arpiagrama.operational.context;

import com.example.arpiagrama.operational.preferences.OpenAiApiKeyPreferences;

import android.app.Activity;
import android.content.res.Configuration;
import android.net.Uri;
import androidx.appcompat.app.AlertDialog;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.text.method.ArrowKeyMovementMethod;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.arpiagrama.BuildConfig;
import com.example.arpiagrama.R;
import com.example.arpiagrama.operational.audio.SpeechController;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class ContextDescriptionManager {
    private static final String TAG = "ContextDescriptionManager";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_STRUCTURED_DATA_LENGTH = 50_000;
    private static final String OPENAI_MODEL = "gpt-5.2";
    private static final String OPENAI_CHAT_COMPLETIONS_URL = "https://api.openai.com/v1/chat/completions";

    private final Activity activity;
    private final SpeechController speechController;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final OkHttpClient httpClient = new OkHttpClient();

    private AlertDialog progressDialog;
    private AlertDialog descriptionDialog;
    private boolean requestInProgress;

    public ContextDescriptionManager(@NonNull Activity activity, @NonNull SpeechController speechController) {
        this.activity = activity;
        this.speechController = speechController;
    }

    public boolean isRequestInProgress() {
        return requestInProgress;
    }

    public boolean isInteractionActive() {
        boolean showingProgress = progressDialog != null && progressDialog.isShowing();
        boolean showingDescription = descriptionDialog != null && descriptionDialog.isShowing();
        return requestInProgress || showingProgress || showingDescription;
    }

    public void requestDescription(@NonNull String structuredDiagramData) {
        if (requestInProgress) {
            speechController.speakQueued(activity.getString(R.string.context_description_in_progress));
            return;
        }

        String endpoint = getContextDescriptionEndpoint();
        String apiKey = OpenAiApiKeyPreferences.get(activity);
        if (!isStructuredDiagramDataValid(structuredDiagramData)) {
            showError(activity.getString(R.string.context_description_empty_response));
            return;
        }

        // The remote service improves the wording, but it must not be required for the
        // accessibility feature to work. This also keeps APKs built locally useful without
        // embedding an API credential in the application.
        if (endpoint.isEmpty() && apiKey.isEmpty()) {
            showDescription(buildLocalContextDescription(structuredDiagramData));
            return;
        }

        requestInProgress = true;
        showProgress();

        executorService.execute(() -> {
            try {
                String description = endpoint.isEmpty()
                        ? requestFromOpenAi(buildPrompt(structuredDiagramData), apiKey)
                        : requestFromBackend(endpoint, structuredDiagramData, "description");
                activity.runOnUiThread(() -> {
                    requestInProgress = false;
                    hideProgress();
                    showDescription(description != null && !description.trim().isEmpty()
                            ? description.trim()
                            : buildLocalContextDescription(structuredDiagramData));
                });
            } catch (Exception e) {
                Log.e(TAG, "Error requesting context description", e);
                activity.runOnUiThread(() -> {
                    requestInProgress = false;
                    hideProgress();
                    showDescription(buildLocalContextDescription(structuredDiagramData));
                });
            }
        });
    }

    public void shutdown() {
        hideProgress();
        if (descriptionDialog != null && descriptionDialog.isShowing()) {
            descriptionDialog.dismiss();
        }
        descriptionDialog = null;
        requestInProgress = false;
        executorService.shutdownNow();
        httpClient.dispatcher().cancelAll();
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    @NonNull
    public String generateShortAltTextForSave(@NonNull String structuredDiagramData) {
        if (!isStructuredDiagramDataValid(structuredDiagramData)) {
            return activity.getString(R.string.export_alt_text_fallback_empty);
        }

        String fallback = buildLocalAltText(structuredDiagramData);
        String endpoint = getContextDescriptionEndpoint();
        String apiKey = OpenAiApiKeyPreferences.get(activity);
        if (endpoint.isEmpty() && apiKey.isEmpty()) {
            return fallback;
        }

        try {
            String response = endpoint.isEmpty()
                    ? requestFromOpenAi(buildAltTextPrompt(structuredDiagramData), apiKey)
                    : requestFromBackend(endpoint, structuredDiagramData, "alt_text");
            if (response == null) {
                return fallback;
            }
            String sanitized = sanitizeAltText(response);
            return sanitized.isEmpty() ? fallback : sanitized;
        } catch (Exception e) {
            Log.w(TAG, "Falling back to local alt text for export", e);
            return fallback;
        }
    }

    private void showProgress() {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (progressDialog == null) {
            ProgressBar progressBar = new ProgressBar(activity);
            progressBar.setIndeterminate(true);
            progressDialog = new AlertDialog.Builder(activity)
                    .setTitle(R.string.context_description_title)
                    .setMessage(R.string.context_description_requesting)
                    .setView(progressBar)
                    .setCancelable(false)
                    .create();
        } else {
            progressDialog.setMessage(activity.getString(R.string.context_description_requesting));
        }
        progressDialog.show();
    }

    private void hideProgress() {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }

    private void showDescription(@NonNull String description) {
        if (!activity.isFinishing() && !activity.isDestroyed()) {
            float density = activity.getResources().getDisplayMetrics().density;

            TextView descriptionView = new TextView(activity);
            descriptionView.setText(description);
            descriptionView.setTextIsSelectable(true);
            descriptionView.setFocusable(true);
            descriptionView.setFocusableInTouchMode(true);
            descriptionView.setClickable(true);
            descriptionView.setMovementMethod(ArrowKeyMovementMethod.getInstance());
            descriptionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            descriptionView.setLineSpacing(0f, 1.2f);
            int textHorizontalPadding = Math.round(12f * density);
            int textVerticalPadding = Math.round(8f * density);
            descriptionView.setPadding(textHorizontalPadding, textVerticalPadding, textHorizontalPadding, textVerticalPadding);

            ScrollView scrollView = new ScrollView(activity);
            int containerHorizontalPadding = Math.round(20f * density);
            int containerVerticalPadding = Math.round(12f * density);
            scrollView.setPadding(containerHorizontalPadding, containerVerticalPadding, containerHorizontalPadding, containerVerticalPadding);
            scrollView.addView(descriptionView);

            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(R.string.context_description_title)
                    .setView(scrollView)
                    .setPositiveButton(android.R.string.ok, null)
                    .create();
            descriptionDialog = dialog;
            dialog.setOnDismissListener(d -> descriptionDialog = null);
            dialog.show();

            if (dialog.getWindow() != null) {
                int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92f);
                int height = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.72f);
                dialog.getWindow().setLayout(width, height);
            }

            styleContextDescriptionOkButton(dialog);
            configureDialogAccessibility(dialog, descriptionView);
        }
        speechController.speakQueued(description);
    }

    private void styleContextDescriptionOkButton(@NonNull AlertDialog dialog) {
        Button okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (okButton == null) return;
        boolean isDarkTheme = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        float density = activity.getResources().getDisplayMetrics().density;
        int buttonBackgroundColor = isDarkTheme ? Color.parseColor("#1E1E1E") : Color.WHITE;
        int buttonTextAndStrokeColor = isDarkTheme ? Color.parseColor("#F5F5F5") : Color.BLACK;
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(buttonBackgroundColor);
        shape.setCornerRadius(12f * density);
        shape.setStroke(Math.max(5, Math.round(5f * density)), buttonTextAndStrokeColor);
        okButton.setBackgroundTintList(null);
        okButton.setBackground(shape);
        okButton.setTextColor(buttonTextAndStrokeColor);
        okButton.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                Math.max(18, okButton.getTextSize() / activity.getResources().getDisplayMetrics().scaledDensity)
        );
        int horizontalPadding = Math.round(24f * density);
        int verticalPadding = Math.round(12f * density);
        okButton.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
        okButton.setMinHeight(Math.round(56f * density));
    }

    private void configureDialogAccessibility(@NonNull AlertDialog dialog, @Nullable TextView descriptionView) {
        if (descriptionView != null) {
            descriptionView.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    speechController.stopAll();
                    speechController.speakImmediate(resolveViewAnnouncement(v));
                }
            });
        }
        View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button != null) {
            button.setFocusable(true);
            button.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    speechController.stopAll();
                    speechController.speakImmediate(resolveViewAnnouncement(v));
                }
            });
        }
    }

    @NonNull
    private String resolveViewAnnouncement(@NonNull View view) {
        if (view.getContentDescription() != null && view.getContentDescription().length() > 0) {
            return view.getContentDescription().toString();
        }
        if (view instanceof EditText) {
            EditText input = (EditText) view;
            CharSequence hint = input.getHint();
            if (hint != null && hint.length() > 0) {
                return hint + ", campo de inserir texto";
            }
            return "Campo de inserir texto";
        }
        if (view instanceof Button) {
            CharSequence text = ((Button) view).getText();
            if (text != null && text.length() > 0) {
                return text.toString();
            }
        }
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0) {
                return text.toString();
            }
        }
        return "";
    }

    private void showError(@NonNull String message) {
        speechController.speakQueued(message);
    }

    @NonNull
    private String getContextDescriptionEndpoint() {
        String endpoint = BuildConfig.CONTEXT_DESCRIPTION_ENDPOINT;
        if (endpoint == null) {
            return "";
        }
        endpoint = endpoint.trim();
        if (endpoint.isEmpty()) {
            return "";
        }

        Uri uri = Uri.parse(endpoint);
        String scheme = uri.getScheme();
        boolean isHttps = "https".equalsIgnoreCase(scheme);
        boolean isDebugHttp = BuildConfig.DEBUG && "http".equalsIgnoreCase(scheme);
        if (!isHttps && !isDebugHttp) {
            Log.w(TAG, "Context description endpoint must use HTTPS outside debug builds.");
            return "";
        }
        return endpoint;
    }

    private boolean isStructuredDiagramDataValid(@NonNull String structuredDiagramData) {
        String trimmed = structuredDiagramData.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_STRUCTURED_DATA_LENGTH) {
            return false;
        }
        try {
            JSONObject payload = new JSONObject(trimmed);
            return payload.has("pecas") && payload.has("conexoes");
        } catch (JSONException e) {
            Log.w(TAG, "Invalid structured diagram data", e);
            return false;
        }
    }

    @NonNull
    private String buildPrompt(@NonNull String structuredDiagramData) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Você receberá dados estruturados de um diagrama de caso de uso, em JSON. ")
                .append("Sua resposta deve usar esses dados e tambem a visão computacional na imagem.\n")
                .append("Regras obrigatórias:\n")
                .append("1) É proibido inventar nomes, conexões, tipos ou qualquer elemento ausente nos dados.\n")
                .append("2) Quando houver nome definido pelo usuário, use exatamente o mesmo texto.\n")
                .append("3) Quando uma peça estiver sem nome, escreva exatamente: sem nome definido.\n")
                .append("4) Se houver dúvida, inconsistência ou falta de informação, escreva que não foi possível confirmar, sem adivinhar.\n")
                .append("5) Não cite cores, não cite ids, não cite posições visuais, não descreva formas, não use markdown, nem P1, C2, R2 ou algo pareceido.\n")
                .append("6) Responda em português, de forma acessível para pessoas com deficiência visual.\n")
                .append("7) Se peca_alvo_indicador existir: descreva somente essa peça, informando tipo do elemento, nome marcado no bounding box e com quais outros elementos ela se conecta.\n")
                .append("8) Se peca_alvo_indicador não existir: faça uma descrição resumida do diagrama completo com base em pecas e conexoes fornecidas.\n")
                .append("Retorne apenas a descrição final em um único parágrafo e de forma resumida.")
                .append("Dados estruturados do diagrama (JSON):\n")
                .append(structuredDiagramData);
        return prompt.toString();
    }

    @NonNull
    private String buildAltTextPrompt(@NonNull String structuredDiagramData) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Você receberá dados estruturados de um diagrama de caso de uso, em JSON. ")
                .append("Crie um texto alternativo curto e acessível para pessoas com deficiência visual.\n")
                .append("Regras obrigatórias:\n")
                .append("1) Responda em português do Brasil.\n")
                .append("2) Use no máximo 160 caracteres e apenas uma frase.\n")
                .append("3) Não invente nomes, conexões, tipos ou ações ausentes no JSON.\n")
                .append("4) Quando não houver nome, escreva sem nome definido.\n")
                .append("5) Não cite cores, posições, formas, coordenadas ou IDs como P1.\n")
                .append("6) Resuma somente o que ajuda a entender o diagrama rapidamente.\n")
                .append("Retorne apenas o texto alternativo final.\n")
                .append("Dados estruturados do diagrama (JSON):\n")
                .append(structuredDiagramData);
        return prompt.toString();
    }

    @NonNull
    private String sanitizeAltText(@NonNull String rawText) {
        String sanitized = rawText.replace('\n', ' ').replace('\r', ' ').trim();
        sanitized = sanitized.replaceAll("\\s+", " ");
        if (sanitized.length() > 160) {
            sanitized = sanitized.substring(0, 157).trim() + "...";
        }
        return sanitized;
    }

    @NonNull
    private String buildLocalAltText(@NonNull String structuredDiagramData) {
        try {
            JSONObject payload = new JSONObject(structuredDiagramData);
            JSONArray pieces = payload.optJSONArray("pecas");
            JSONArray connections = payload.optJSONArray("conexoes");
            if (pieces == null || pieces.length() == 0) {
                return activity.getString(R.string.export_alt_text_fallback_empty);
            }

            List<String> summarizedPieces = new ArrayList<>();
            int maxPieces = Math.min(3, pieces.length());
            for (int i = 0; i < maxPieces; i++) {
                JSONObject piece = pieces.optJSONObject(i);
                if (piece == null) {
                    continue;
                }
                String type = piece.optString("tipo", "elemento").trim();
                if (type.isEmpty()) {
                    type = "elemento";
                }
                String name = piece.optString("nome", activity.getString(R.string.export_alt_text_unnamed_piece)).trim();
                if (name.isEmpty()) {
                    name = activity.getString(R.string.export_alt_text_unnamed_piece);
                }
                summarizedPieces.add(type + " " + name);
            }

            int totalPieces = pieces.length();
            int totalConnections = connections == null ? 0 : connections.length();
            StringBuilder altText = new StringBuilder();
            altText.append("Diagrama com ")
                    .append(totalPieces)
                    .append(totalPieces == 1 ? " elemento" : " elementos");
            if (!summarizedPieces.isEmpty()) {
                altText.append(": ");
                for (int i = 0; i < summarizedPieces.size(); i++) {
                    if (i > 0) {
                        altText.append(i == summarizedPieces.size() - 1 ? " e " : ", ");
                    }
                    altText.append(summarizedPieces.get(i));
                }
            }
            if (totalConnections > 0) {
                altText.append(". ")
                        .append(totalConnections)
                        .append(totalConnections == 1 ? " conexão identificada" : " conexões identificadas");
            }
            return sanitizeAltText(altText.toString());
        } catch (JSONException e) {
            Log.w(TAG, "Unable to build local alt text", e);
            return activity.getString(R.string.export_alt_text_fallback_generic);
        }
    }

    @NonNull
    private String buildLocalContextDescription(@NonNull String structuredDiagramData) {
        try {
            JSONObject payload = new JSONObject(structuredDiagramData);
            JSONArray pieces = payload.getJSONArray("pecas");
            JSONArray connections = payload.optJSONArray("conexoes");
            String targetId = payload.isNull("peca_alvo_indicador")
                    ? ""
                    : payload.optString("peca_alvo_indicador", "").trim();

            if (pieces.length() == 0) {
                return activity.getString(R.string.export_alt_text_fallback_empty);
            }

            JSONObject target = findPieceById(pieces, targetId);
            if (target != null) {
                StringBuilder targetedDescription = new StringBuilder("O elemento indicado é ")
                        .append(describePiece(target));
                List<String> neighbours = new ArrayList<>();
                if (connections != null) {
                    for (int i = 0; i < connections.length(); i++) {
                        JSONObject connection = connections.optJSONObject(i);
                        if (connection == null) continue;
                        String origin = connection.optString("origem", "");
                        String destination = connection.optString("destino", "");
                        String neighbourId = targetId.equals(origin) ? destination
                                : targetId.equals(destination) ? origin : "";
                        JSONObject neighbour = findPieceById(pieces, neighbourId);
                        if (neighbour != null) neighbours.add(describePiece(neighbour));
                    }
                }
                if (neighbours.isEmpty()) {
                    targetedDescription.append(". Não foi possível confirmar conexões com esse elemento.");
                } else {
                    targetedDescription.append(". Ele se conecta a ").append(joinNaturally(neighbours)).append('.');
                }
                return targetedDescription.toString();
            }

            List<String> pieceDescriptions = new ArrayList<>();
            int shownPieces = Math.min(8, pieces.length());
            for (int i = 0; i < shownPieces; i++) {
                JSONObject piece = pieces.optJSONObject(i);
                if (piece != null) pieceDescriptions.add(describePiece(piece));
            }

            int connectionCount = connections == null ? 0 : connections.length();
            StringBuilder description = new StringBuilder("O diagrama contém ")
                    .append(pieces.length())
                    .append(pieces.length() == 1 ? " elemento" : " elementos")
                    .append(": ")
                    .append(joinNaturally(pieceDescriptions));
            if (pieces.length() > shownPieces) {
                description.append(", além de outros ").append(pieces.length() - shownPieces).append(" elementos");
            }
            description.append(". Foram identificadas ").append(connectionCount)
                    .append(connectionCount == 1 ? " conexão" : " conexões").append('.');
            return description.toString();
        } catch (JSONException e) {
            Log.w(TAG, "Unable to build local context description", e);
            return activity.getString(R.string.export_alt_text_fallback_generic);
        }
    }

    @Nullable
    private JSONObject findPieceById(@NonNull JSONArray pieces, @NonNull String id) {
        if (id.isEmpty()) return null;
        for (int i = 0; i < pieces.length(); i++) {
            JSONObject piece = pieces.optJSONObject(i);
            if (piece != null && id.equals(piece.optString("id", ""))) return piece;
        }
        return null;
    }

    @NonNull
    private String describePiece(@NonNull JSONObject piece) {
        String type = piece.optString("tipo", "tipo não confirmado").trim();
        String name = piece.optString("nome", "sem nome definido").trim();
        if (type.isEmpty()) type = "tipo não confirmado";
        if (name.isEmpty()) name = "sem nome definido";
        return type + " " + name;
    }

    @NonNull
    private String joinNaturally(@NonNull List<String> values) {
        if (values.isEmpty()) return "nenhum elemento confirmado";
        if (values.size() == 1) return values.get(0);
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) joined.append(i == values.size() - 1 ? " e " : ", ");
            joined.append(values.get(i));
        }
        return joined.toString();
    }

    private String requestFromBackend(@NonNull String endpoint,
                                      @NonNull String structuredDiagramData,
                                      @NonNull String requestType) throws IOException, JSONException {
        JSONObject requestBodyJson = new JSONObject();
        requestBodyJson.put("request_type", requestType);
        requestBodyJson.put("structured_diagram_data", new JSONObject(structuredDiagramData));

        RequestBody body = RequestBody.create(requestBodyJson.toString(), JSON_MEDIA_TYPE);

        Request request = new Request.Builder()
                .url(endpoint)
                .post(body)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("Empty response body");
            }
            String responseString = responseBody.string();
            return extractDescriptionFromBackend(responseString);
        }
    }

    private String requestFromOpenAi(@NonNull String prompt, @NonNull String apiKey)
            throws IOException, JSONException {
        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", prompt);

        JSONArray messages = new JSONArray();
        messages.put(message);

        JSONObject requestBodyJson = new JSONObject();
        requestBodyJson.put("model", OPENAI_MODEL);
        requestBodyJson.put("messages", messages);

        Request request = new Request.Builder()
                .url(OPENAI_CHAT_COMPLETIONS_URL)
                .post(RequestBody.create(requestBodyJson.toString(), JSON_MEDIA_TYPE))
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("OpenAI request failed with HTTP " + response.code());
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("Empty OpenAI response body");
            }
            return extractDescriptionFromBackend(responseBody.string());
        }
    }

    private String extractDescriptionFromBackend(@NonNull String responseString) throws JSONException {
        String trimmed = responseString.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (!trimmed.startsWith("{")) {
            return trimmed;
        }

        JSONObject json = new JSONObject(trimmed);
        String description = json.optString("description", "").trim();
        if (!description.isEmpty()) {
            return description;
        }
        String text = json.optString("text", "").trim();
        if (!text.isEmpty()) {
            return text;
        }
        String altText = json.optString("alt_text", "").trim();
        if (!altText.isEmpty()) {
            return altText;
        }
        String content = json.optString("content", "").trim();
        if (!content.isEmpty()) {
            return content;
        }

        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            return null;
        }
        JSONObject firstChoice = choices.getJSONObject(0);
        JSONObject message = firstChoice.optJSONObject("message");
        if (message == null) {
            return null;
        }
        return message.optString("content", null);
    }

    @Nullable
    public AlertDialog getDescriptionDialogIfShowing() {
        if (descriptionDialog != null && descriptionDialog.isShowing()) {
            return descriptionDialog;
        }
        return null;
    }
}
