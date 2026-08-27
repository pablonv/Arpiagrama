# Arquitetura em cinco camadas

O Arpiagrama segue o fluxo **ambiente físico → aquisição → processamento visual → interpretação → operação/UI**. A refatoração é incremental: o fluxo funcional foi movido sem reescrita e novos contratos de domínio impedem que tipos de YOLO, TensorFlow Lite ou MediaPipe atravessem os limites novos.

## Mapeamento e responsabilidades

| Camada | Pacotes | Responsabilidade |
|---|---|---|
| Physical | `physical` | Configuração da mesa e da grade 3x3, sem regra de negócio. |
| Acquisition | `acquisition.camera` | Camera2, frames, orientação, resolução, ROI e transformações de imagem. |
| Visual Processing | `visualprocessing.model`, `tracking`, `infrastructure` | Modelo neutro de detecção, estabilidade/oclusão e adapters YOLO/TFLite e MediaPipe. |
| Interpretation | `interpretation.model`, `service`, `infrastructure` | Modelo UML e conversão semântica de detecções confirmadas. O adapter de IA deve permanecer em `infrastructure`. |
| Operational | `operational.ui`, `preferences`, `persistence`, `audio`, `rendering`, `context` | Activities, acessibilidade, navegação, áudio, renderização, preferências, exportação e persistência. |

## Dependências

- `operational` orquestra casos de uso e consome `interpretation`;
- `interpretation` conhece somente os modelos neutros publicados por `visualprocessing.model`;
- adapters de `visualprocessing.infrastructure` encapsulam TFLite/YOLO e MediaPipe;
- `acquisition` encapsula Camera2 e entrega imagens ao pipeline visual;
- `physical` contém apenas dados do ambiente.

## Decisões incrementais

`MainActivity` ainda coordena captura, interpretação interativa, exportação e apresentação porque esses fluxos compartilham estado de sessão extenso. Movê-los de uma vez aumentaria o risco de regressão. Os limites de pacote e os modelos `DetectedElement`/`UmlDiagram` criam pontos seguros para extrações posteriores. `Recognition` foi mantido temporariamente como modelo de compatibilidade interno ao pipeline existente. O detector MediaPipe antigo e não utilizado foi removido; a detecção ativa por YOLO e o detector de mãos foram preservados.
