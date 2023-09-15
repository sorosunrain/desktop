/*
 * Copyright (C) 2019 Drakeet Xu <drakeet@drakeet.com>
 *
 * This file is part of Pure Writer Desktop
 *
 * Pure Writer Desktop is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * rebase-server is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with rebase-server. If not, see <http://www.gnu.org/licenses/>.
 */

package com.drakeet.purewriter

import io.netty.channel.Channel
import io.reactivex.Completable
import io.reactivex.disposables.Disposable
import io.reactivex.rxjavafx.observables.JavaFxObservable
import io.reactivex.rxjavafx.schedulers.JavaFxScheduler.platform
import io.reactivex.schedulers.Schedulers
import io.reactivex.schedulers.Schedulers.computation
import javafx.application.Platform
import javafx.beans.value.ChangeListener
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.control.IndexRange
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.text.Font
import javafx.util.Duration
import java.net.URL
import java.util.*


class PureWriter : Initializable {

  private val version = "0.3.1"
  private val appVersion = "17.7.10"

  @FXML
  private lateinit var emptyView: Label

  @FXML
  private lateinit var rootLayout: StackPane

  @FXML
  private lateinit var ipLayout: VBox

  @FXML
  private lateinit var ipNoteLabel: Label

  @FXML
  private lateinit var ipBottomLabel: Label

  @FXML
  private lateinit var ipView: TextField

  @FXML
  private lateinit var contentView: TextArea

  @FXML
  private lateinit var dayNightSwitch: ImageView

  private val client = Client()

  private val channel: Channel? get() = client.channel

  private var title = "Untitled"

  override fun initialize(location: URL?, resources: ResourceBundle?) {
    rootLayout.styleClass.addAll("pane")
    ipLayout.styleClass.addAll("pane")

    contentView.font = Font.loadFont(javaClass.getResourceAsStream("SourceHanSansCN-Light.ttf"), 16.toDouble())
    ipNoteLabel.font = Font.loadFont(javaClass.getResourceAsStream("SourceHanSansCN-Light.ttf"), 14.toDouble())
    ipBottomLabel.font = ipNoteLabel.font
    emptyView.font = ipNoteLabel.font
    ipView.font = Font.loadFont(javaClass.getResourceAsStream("SourceHanSansCN-Light.ttf"), 18.toDouble())
    dayNightSwitch.image = Image(javaClass.getResourceAsStream("moon.png"))
    dayNightSwitch.setOnMouseClicked {
      Settings.darkMode = (!Settings.darkMode)
        .also { mainStage.scene.setDarkMode(it) }
    }
    showIpViews()
    ipView.text = Settings.ip

    RxBus.event(ChannelActive::class)
      .observeOn(platform())
      .subscribe {
        if (it.active) {
          // hideIP()
          // Wait for the article
        } else {
          showIpViews()
        }
      }

    RxBus.event(ArticleMessage::class)
      .subscribe {
        beginSyncing()
        mainStage.title = (if (it.title.isNotEmpty()) it.title else "Untitled").apply { title = this }
        contentView.replaceText(it.content)
        contentView.isVisible = true
        // workaround to trigger focus
        runCatching { contentView.selectRange(it.selectionStart - 1, it.selectionEnd - 1) }
        Platform.runLater { contentView.selectRange(it.selectionStart, it.selectionEnd) }
        isSyncingSelection = false
        isSyncingTitle = false
        hideIP()
        hideEmpty()
      }

    RxBus.event(WordCountMessage::class)
      .subscribe {
        mainStage.title = "$title · ${it.text}"
      }

    RxBus.event(EmptyArticleMessage::class)
      .subscribe {
        showEmpty()
        hideIP()
      }

    contentView.textProperty().addListener { _, _, newValue ->
      Log.d("newText: $newValue")
      if (channel?.isActive != true) {
        showIpViews()
        return@addListener
      }
      if (!isSyncing) {
        invalidate(content = newValue)
      }
      isSyncingContent = false
    }
    contentView.selectionProperty().addListener { _, _, newValue ->
      if (channel?.isActive != true) {
        showIpViews()
        return@addListener
      }
      if (!isSyncing) {
        invalidate(selection = newValue)
      }
      isSyncingSelection = false
    }

    JavaFxObservable.interval(Duration.seconds(1.0))
      .subscribeOn(computation())
      .observeOn(platform())
      .subscribe {
        if (channel?.isActive != true) {
          showIpViews()
        } else {
          PingPongDelegate.ping(channel!!)
        }
      }
  }

  private fun showEmpty() {
    emptyView.text = if (isZh) {
      """
      未打开任何文章
      请在纯纯写作手机版中打开一篇文章

      纯纯写作桌面版 v$version
    """
        .trimIndent()
        .toTraditionalIfNeeded()
    } else {
      """
        No article selected
        Please open an article on Pure Writer mobile
        
        Pure Writer Desktop v$version
      """
        .trimIndent()
    }
    emptyView.isVisible = true
    isSyncingContent = true
    contentView.replaceText("")
    contentView.isVisible = false
    isSyncingTitle = true
    mainStage.title = stageTitle
  }

  private fun hideEmpty() {
    if (emptyView.isVisible) {
      emptyView.isVisible = false
    }
  }

  private var isIpViewsInitialized = false

  private fun showIpViews() {
    lastConnectingIp = null
    if (ipLayout.isVisible && isIpViewsInitialized) {
      if (ipView.text.isNotBlank() && !PingPongDelegate.isJustDisconnected) {
        ipObserver.changed(null, ipView.text, ipView.text)
      } else {
        ipView.text = Settings.ip
        PingPongDelegate.isJustDisconnected = false
      }
      return
    }
    ipNoteLabel.text = if (isZh) {
      """
        未连接或与手机断开了啊
        请打开纯纯写作 Android 版并点击其顶部的云图标获得 IP 地址填于下方
        一旦输入正确 IP，它将自动连接
        提示：
        * 你可以在 IP 地址中输入中文句号来替代英文句号，比如：1。1。1。1 将会被识别为 1.1.1.1
        * 如果您的 IP 地址是以 192.168.1 开头，则您可以直接输入最后一组数字即可自动连接
      """
        .trimIndent()
        .toTraditionalIfNeeded()
    } else {
      """
        Unconnected or disconnected
        Please open Pure Writer for Android and click its top cloud icon 
        to get an IP address into the below input field
        Once the correct IP is entered, it will be auto connected
        Tips:
        * If your IP address starts with 192.168.1, you can enter the last number directly to connect automatically
      """
        .trimIndent()
    }

    ipBottomLabel.text = if (isZh) {
      """
        纯纯写作桌面版 v$version  ⇋  纯纯写作 v$appVersion+
        当前「纯纯写作桌面版」必须和「纯纯写作」v$appVersion 或以上版本才能正常协作
      """
        .trimIndent()
        .toTraditionalIfNeeded()
    } else {
      """
        Pure Writer Desktop v$version  ⇋  Pure Writer v$appVersion+
        The current Desktop only works with Pure Writer for Android v$appVersion or above!
      """.trimIndent()
    }

    ipLayout.isVisible = true
    isSyncingTitle = true
    mainStage.title = "Pure Writer"
    isSyncingTitle = false
    isSyncingContent = true
    contentView.replaceText("")
    Platform.runLater { ipView.requestFocus() }
    ipView.textProperty().removeListener(ipObserver)
    ipView.textProperty().addListener(ipObserver)
    isIpViewsInitialized = true
  }

  private val ipObserver = ChangeListener<String> { _, _, newValue ->
    var ip = newValue.trim().replace("。", ".")
    if (!ip.contains(".")) {
      ip = "192.168.1.$ip"
    }
    Log.d("Input IP: $ip")
    if (ip.matches("((2(5[0-5]|[0-4]\\d))|[0-1]?\\d{1,2})(\\.((2(5[0-5]|[0-4]\\d))|[0-1]?\\d{1,2})){3}".toRegex())) {
      startNettyClient(ip)
    }
  }

  private fun hideIP() {
    ipView.textProperty().removeListener(ipObserver)
    ipLayout.isVisible = false
    isIpViewsInitialized = false
  }

  private fun invalidate(
    title: String = mainStage.title,
    content: String = contentView.text,
    selection: IndexRange = contentView.selection
  ) {
    Log.d("invalidate: $title, $content, $selection")
    channel?.sendArticle(
      title = title,
      content = content,
      start = selection.start,
      end = selection.end
    )
  }

  private var clientDisposable: Disposable? = null

  private var lastConnectingIp: String? = null

  private fun startNettyClient(ip: String) {
    if (lastConnectingIp == ip || ip.isBlank()) return
    lastConnectingIp = ip
    Settings.ip = ip
    clientDisposable?.dispose()
    clientDisposable = Completable.fromAction { client.start(ip, 19621) }
      .retry(1)
      .subscribeOn(Schedulers.newThread())
      .subscribe({}, { it.printStackTrace() })
  }
}
