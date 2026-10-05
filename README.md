# Nuvio Enhanced Desktop

**Uma evolução open source do Nuvio para PC, com melhorias integradas ao cliente nativo existente.** Este é um fork independente e não oficial de [NuvioMedia/NuvioDesktop](https://github.com/NuvioMedia/NuvioDesktop).

A base continua em Kotlin Multiplatform/Compose, commonMain e libmpv/JNI, com a integração WebView2 existente no Windows. Conta, perfis, biblioteca, progresso, addons Stremio, legendas e downloads continuam usando os módulos atuais. O objetivo é ampliar o Nuvio preservando seus contratos.

## Download

[Baixar a pré-release do Nuvio Enhanced](https://github.com/Pepeu2010/nuvio-enhanced/releases/tag/v0.1.0-alpha.2): instalador **Windows x64 MSI**, fontes e checksums. Essa release contém a fundação 0-C e o incremento 1-A.1; as versões internas são herdadas do upstream. Para trocar a alpha.1 pela alpha.2, remova a instalação anterior do Nuvio Enhanced antes de instalar o novo MSI, pois ambos ainda mantêm a mesma versão/identidade de produto.

O código de main evolui por incrementos. A preferência local por perfil **Movimento de navegação** integra a alpha.2: completo, reduzido e desligado para as transições revisadas. Home, previews e os demais efeitos serão tratados nas etapas seguintes. O milestone visual completo ainda está em execução.

O incremento **1-A.2 em main, ainda fora da alpha.2**, estende essa política ao shell, tokens, hover dos posters e skeletons. Nos modos reduzidos, a navegação Jelly mantém clique/drag sem elasticidade ou loop de frames, e os rótulos adaptativos ficam estáveis. Passaram 25 testes direcionados, incluindo interação real dos componentes Compose, e o MSI foi compilado. Outros efeitos e a identidade visual completa continuam pendentes.

O incremento **1-A.3, também fora da alpha.2**, acrescenta intensidade Sutil, Padrão e Cinemática por perfil, integrada à mesma área Aparência. Reduzido e Desligado prevalecem sobre a intensidade. Passaram 32 testes direcionados, incluindo seleção real com mouse/teclado e persistência em armazenamento isolado, e o MSI foi compilado. A preferência local é preservada ao substituir os dados de sync e não altera seus contratos. [Evidências](https://github.com/Pepeu2010/nuvio-enhanced/blob/main/docs/intensity-ui-qa.json).

A continuação no **menu lateral padrão** integra sua expansão, rótulos e offsets à política de movimento. No modo adaptativo Reduzido/Desligado, o menu e o padding ficam estáveis; Compacto permanece uma opção explícita. Os itens expõem seleção acessível. Passaram 27 testes direcionados, incluindo clique e Enter no componente lateral real, e o MSI foi compilado. A captura/teste do componente não substitui QA integral do app. Esta continuação também está fora da alpha.2.

## O que muda e o que vem depois

A fundação já tem instalação/dados/cache/updater próprios, relatórios externos de falhas desligados por padrão, redaction nos diagnósticos revisados e correção da recompilação da ponte Windows.

Estão previstos: identidade e interface cinematográficas, Home/hero aprimorados, Profile Studio com avatares locais, evolução do preview, Ambient UI, timeline com thumbnails reais/filmstrip/bookmarks, Source Intelligence, cache Auto/configurável e, depois, Live TV/EPG, Scene Info e controle local pelo celular.

Esses recursos são integrados aos componentes existentes conforme o [roadmap](https://github.com/Pepeu2010/nuvio-enhanced/blob/main/docs/ROADMAP.md). Não são todos recursos prontos. A fundação passou em 31 testes direcionados Desktop; o baseline completo registrou 26 falhas herdadas e 1 teste ignorado. Instalação, login/sync, playback e QA completo ainda precisam de validação. [Evidências e limites](https://github.com/Pepeu2010/nuvio-enhanced/blob/main/docs/FOUNDATION.md).

## Compilar e executar

Use JDK 17 e Git LFS. As versões de ferramentas, configurações públicas de desenvolvimento e instruções do SDK WebView2 são registradas em [BASELINE.md](https://github.com/Pepeu2010/nuvio-enhanced/blob/main/docs/BASELINE.md). O workspace central oferece scripts para restaurar/provisionar os checkouts sem guardar credenciais no Git.

```powershell
git clone https://github.com/Pepeu2010/nuvio-enhanced-desktop.git
cd nuvio-enhanced-desktop
git lfs pull

# Configure o SDK WebView2 local conforme BASELINE.md
.\gradlew.bat :composeApp:run --no-configuration-cache "-Pnuvio.webview2.dir=<caminho do SDK>"
.\gradlew.bat :composeApp:packageReleaseMsi --no-configuration-cache "-Pnuvio.webview2.dir=<caminho do SDK>"
```

Em hosts compatíveis, os comandos de packaging upstream permanecem:

```bash
./gradlew :composeApp:packageReleaseDistributionForCurrentOS
# macOS: execute no macOS
./scripts/build-macos-release-dmgs.sh --package-only
# Linux: execute no Linux
./gradlew :composeApp:packageReleaseDeb
```

Ainda não há pacotes Linux/macOS publicados pelo fork. Não remova recursos ou troque o player para simplificar um build.

## Organização e versões

- `composeApp/src/commonMain/`: UI, features, repositories e regras compartilhadas existentes.
- `composeApp/src/desktopMain/`: integrações de plataforma.
- `composeApp/Configuration/DesktopVersion.properties`: versão e código de build Desktop.

O helper original continua disponível:

```bash
./scripts/set-version.sh --show
# Exemplo para uma futura entrega, não a versão instalada atual:
./scripts/set-version.sh --desktop 0.1.28-alpha --desktop-code 28
```

Antes de criar código novo, localize o componente relacionado e seu ponto de extensão. Teste, compile, valide visualmente e documente. O fluxo de conta/sync não deve ser substituído nem receber endpoints inventados.

## Licença e créditos

[GPL-3.0](LICENSE), copyrights e avisos de terceiros preservados. Leia [FORK_NOTICE.md](FORK_NOTICE.md). O histórico completo conserva a documentação original do upstream. Obrigado aos contribuidores do Nuvio pela base do projeto.

O aplicativo não fornece canais, listas ou conteúdo protegido. Use fontes legítimas que você configure e tenha autorização para acessar. Este fork não afirma vínculo com providers ou endosso do NuvioMedia.
