/**
 * 全息形象渲染层 v5：立绘 + 程序化口型。
 *
 * 图片来自美术（assets/www/avatar/cat.png），代码负责：
 * 1. 按 800×1280 竖屏重新构图（原图 3:4，柜子 5:8，必须上下留黑而不是拉伸）
 * 2. 呼吸、轻微摇摆、光晕脉动 —— 让静态图"活"起来
 * 3. 口型：在嘴的位置叠一个随音量张开的"口腔 + 舌头"，音量归零时完全不可见
 * 4. 全息质感：横向扫描条纹 + 边缘辉光
 *
 * 换形象只需替换 avatar/cat.png，并微调 MOUTH 常量（嘴的位置）。
 */
(function (global) {
  'use strict';

  var TAU = Math.PI * 2;

  // 嘴在图中的相对位置（0~1），换图后需要重新标定
  var MOUTH = {
    x: 0.500,     // 0.5 = 画面水平居中
    y: 0.470,     // 嘴线所在的高度比例
    w: 0.105,     // 张嘴最大宽度（相对图宽）
    h: 0.062      // 张嘴最大高度（相对图宽）
  };

  function AvatarStage(canvas) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');

    this.W = 0;
    this.H = 0;
    this.dpr = 1;

    this.level = 0;
    this.mouth = 0;
    this.state = 'idle';

    this.img = null;
    this.imgReady = false;

    this.t0 = performance.now();
    this.lastFrame = 0;
    this.raf = null;
    this.cache = {};

    this._loop = this._loop.bind(this);
    this.resize = this.resize.bind(this);
    global.addEventListener('resize', this.resize);
    this.resize();

    this._loadImage();
  }

  AvatarStage.prototype._loadImage = function () {
    var im = new Image();
    im.onload = function () {
      this.img = im;
      this.imgReady = true;
    }.bind(this);
    im.onerror = function () {
      // 图没加载出来时保持黑屏，避免闪一下报错
      this.imgReady = false;
    }.bind(this);
    im.src = 'cat.png';
  };

  AvatarStage.prototype.resize = function () {
    var rect = this.canvas.getBoundingClientRect();
    this.dpr = Math.min(global.devicePixelRatio || 1, 2);
    this.W = Math.max(1, Math.round(rect.width));
    this.H = Math.max(1, Math.round(rect.height));

    this.canvas.width = Math.round(this.W * this.dpr);
    this.canvas.height = Math.round(this.H * this.dpr);

    this.ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
    this._buildCache();
  };

  AvatarStage.prototype._buildCache = function () {
    var W = this.W, H = this.H, ctx = this.ctx;

    // 原图 3:4，柜子 5:8 —— 用"宽度撑满 + 轻微放大"而不是拉伸，
    // 这样猫的比例不变形，上下多出来的部分用纯黑自然过渡。
    var scale = W / (this.img ? this.img.width : 618) * 1.06;
    var dh = (this.img ? this.img.height : 795) * scale;
    var dy = H * 0.035;                     // 整体略微上移，给底部字幕留空间

    this.layout = {
      x: (W - (this.img ? this.img.width : 618) * scale) / 2,
      y: dy,
      w: (this.img ? this.img.width : 618) * scale,
      h: dh
    };

    this.eyeGlow = ctx.createRadialGradient(W / 2, H * 0.30, 0, W / 2, H * 0.30, W * 0.55);
    this.eyeGlow.addColorStop(0, 'rgba(255, 180, 90, 0.10)');
    this.eyeGlow.addColorStop(1, 'rgba(255, 180, 90, 0)');
  };

  AvatarStage.prototype.setLevel = function (v) {
    this.level = Math.max(0, Math.min(1, v || 0));
  };

  AvatarStage.prototype.setState = function (s) {
    this.state = s || 'idle';
  };

  AvatarStage.prototype.start = function () {
    if (this.raf) return;
    this.raf = requestAnimationFrame(this._loop);
  };

  AvatarStage.prototype.stop = function () {
    if (this.raf) cancelAnimationFrame(this.raf);
    this.raf = null;
  };

  AvatarStage.prototype._loop = function (now) {
    this.raf = requestAnimationFrame(this._loop);
    if (now - this.lastFrame < 33) return;      // 锁 30fps
    this.lastFrame = now;
    this._update(now);
    this._draw(now);
  };

  AvatarStage.prototype._update = function () {
    // 张嘴快、闭嘴慢，避免抽搐
    var k = this.level > this.mouth ? 0.45 : 0.18;
    this.mouth += (this.level - this.mouth) * k;
    if (this.mouth < 0.005) this.mouth = 0;
  };

  AvatarStage.prototype._draw = function (now) {
    var ctx = this.ctx, W = this.W, H = this.H;
    var t = (now - this.t0) / 1000;

    ctx.clearRect(0, 0, W, H);
    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, W, H);

    if (!this.imgReady || !this.img) return;

    var L = this.layout;
    var breathe = Math.sin(t * (TAU / 4.4)) * (H * 0.0035);
    var sway = Math.sin(t * (TAU / 7.6)) * (W * 0.0016);

    ctx.save();
    ctx.translate(sway, breathe);

    // 身后柔光，让主体从黑底里"浮"出来
    ctx.fillStyle = this.eyeGlow;
    ctx.fillRect(0, 0, W, H);

    // 主体
    ctx.drawImage(this.img, L.x, L.y, L.w, L.h);

    // 口型
    this._drawMouth(ctx, L);

    // 全息横纹（只覆盖主体区域）
    this._drawScanlines(ctx, L);

    ctx.restore();
  };

  AvatarStage.prototype._drawMouth = function (ctx, L) {
    if (this.mouth <= 0.02) return;

    var cx = L.x + L.w * MOUTH.x;
    var cy = L.y + L.h * MOUTH.y;

    var w = L.w * MOUTH.w * (0.55 + 0.45 * this.mouth);
    var h = L.w * MOUTH.h * this.mouth;

    // 口腔
    ctx.save();
    ctx.beginPath();
    ctx.ellipse(cx, cy + h * 0.35, w, h, 0, 0, TAU);
    ctx.fillStyle = '#4E1F16';
    ctx.fill();
    ctx.strokeStyle = 'rgba(120, 52, 30, 0.85)';
    ctx.lineWidth = Math.max(1.5, L.w * 0.004);
    ctx.stroke();
    ctx.restore();

    // 舌头
    ctx.save();
    ctx.beginPath();
    ctx.ellipse(cx, cy + h * 0.35, w, h, 0, 0, TAU);
    ctx.clip();
    ctx.beginPath();
    ctx.ellipse(cx, cy + h * 1.05, w * 0.62, h * 0.55, 0, 0, TAU);
    ctx.fillStyle = '#F08F92';
    ctx.fill();
    ctx.restore();
  };

  AvatarStage.prototype._drawScanlines = function (ctx, L) {
    ctx.save();
    ctx.beginPath();
    ctx.rect(L.x, L.y, L.w, L.h);
    ctx.clip();
    ctx.globalCompositeOperation = 'lighter';
    ctx.fillStyle = 'rgba(255, 210, 150, 0.035)';
    for (var y = L.y; y < L.y + L.h; y += 4) {
      ctx.fillRect(L.x, y, L.w, 1);
    }
    ctx.restore();
  };

  global.AvatarStage = AvatarStage;
})(window);
