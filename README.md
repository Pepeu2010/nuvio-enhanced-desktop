# Telumia Desktop

![Telumia](https://raw.githubusercontent.com/Pepeu2010/telumia/main/assets/brand/telumia-banner.png)

Cliente nativo Telumia para **PC — Kotlin Multiplatform, Compose e libmpv**, com foco no Brasil, catálogos configuráveis, perfis, biblioteca e reprodução.

Marca e logo próprios. A entrega 0.2.0-alpha.1 acrescenta aliases brasileiros de Iludida, idioma inicial TMDB pt-BR e seleção do corte brasileiro entre fontes de metadados instaladas, preservando os IDs de capítulos. A edição de 78 capítulos exige uma fonte que a ofereça. Fontes que só fornecem 31 episódios não são artificialmente expandidas.

[Downloads e status](https://github.com/Pepeu2010/telumia/releases) · [Especificação e limites](https://github.com/Pepeu2010/telumia/blob/main/docs/TELUMIA.md) · [Roadmap](https://github.com/Pepeu2010/telumia/blob/main/docs/ROADMAP.md).

## Desenvolvimento

O cliente em desenvolvimento inclui avatar pessoal com arquivo local, clipboard, drag-and-drop, recorte, zoom e biblioteca integrada de 64 ilustrações licenciadas. As fotos ficam no aparelho, separadas do cache e dos payloads da conta. O editor permanece aberto quando o salvamento remoto do perfil falha; perfis locais recriados recebem uma nova identidade para evitar herdar fotos de outro perfil.

O incremento atual passou 149 testes selecionados e o build do MSI Windows, incluindo importação/persistência de avatares, mutações reais de perfis de convidado, proteção de manifestos futuros e downloads GIF com tamanho limitado. As animações da seleção de perfis agora seguem as opções de movimento. A release pública 0.2.0-alpha.1 ainda não contém esses incrementos, e o redesenho completo dos perfis continua em desenvolvimento. [Evidências e pendências](https://github.com/Pepeu2010/telumia/blob/main/docs/PROFILE_STUDIO.md).

```sh
./gradlew :composeApp:run
```

Use as instruções de configuração local do [projeto central](https://github.com/Pepeu2010/telumia). Não publique arquivos de credenciais ou keystores. A implementação completa continua em execução: a nova marca e a busca Brasil não concluem Profile Studio, cache Auto, timeline, Live TV/EPG, Scene Info ou Phone Remote.

## Licença

[GPL-3.0](LICENSE). Copyrights, autoria e avisos de terceiros preservados. [Créditos do código](FORK_NOTICE.md).
