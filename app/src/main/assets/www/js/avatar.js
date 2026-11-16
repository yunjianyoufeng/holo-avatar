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
    y: 0.478,     // 嘴线所在的高度比例（张嘴从这里向下展开）
    w: 0.070,     // 张嘴最大宽度（相对图宽）
    h: 0.052      // 张嘴最大高度（相对图宽）
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

    ctx.restore();
  };

  AvatarStage.prototype._drawMouth = function (ctx, L) {
    var cx = L.x + L.w * MOUTH.x;
    var lipY = L.y + L.h * MOUTH.y;

    // 原图的 ω 笑嘴已经从素材里抹掉了，改由代码画：
    // 闭嘴时画笑嘴，张嘴时笑嘴一边缩小一边被口腔盖住，过渡才自然
    var sm = Math.max(0, 1 - this.mouth * 3);
    if (sm > 0.02) {
      var sw = L.w * 0.055 * sm;     // 笑嘴半宽（随张口而缩小）
      var dip = L.w * 0.016 * sm;    // 嘴角下垂量
      ctx.save();
      ctx.globalAlpha = sm;
      ctx.strokeStyle = 'rgba(112, 64, 42, 0.92)';
      ctx.lineWidth = Math.max(1.6, L.w * 0.0042);
      ctx.lineCap = 'round';
      ctx.beginPath();
      ctx.moveTo(cx - sw, lipY);
      ctx.quadraticCurveTo(cx - sw * 0.45, lipY + dip, cx, lipY + dip * 0.22);
      ctx.quadraticCurveTo(cx + sw * 0.45, lipY + dip, cx + sw, lipY);
      ctx.stroke();
      ctx.restore();
    }

    if (this.mouth <= 0.02) return;

    // 口腔：胶囊形。宽度随张口量小幅变化，避免小声时变成一条又长又扁的黑杠
    var halfW = L.w * MOUTH.w * (0.45 + 0.55 * this.mouth);
    var top = lipY - L.w * 0.008;
    var bottom = Math.max(
      lipY + L.w * 0.009,
      lipY + L.w * MOUTH.h * this.mouth * 1.6
    );
    var r = Math.min((bottom - top) / 2, halfW * 0.85);

    ctx.save();
    roundRectPath(ctx, cx - halfW, top, halfW * 2, bottom - top, r);
    ctx.fillStyle = '#4E1F16';
    ctx.fill();
    ctx.strokeStyle = 'rgba(120, 52, 30, 0.6)';
    ctx.lineWidth = Math.max(1.2, L.w * 0.003);
    ctx.stroke();
    ctx.restore();

    // 舌头：张口够大时才露，且只占中间一小块，不要拉成一条横线
    if (this.mouth > 0.18) {
      ctx.save();
      roundRectPath(ctx, cx - halfW, top, halfW * 2, bottom - top, r);
      ctx.clip();
      var tw = halfW * 0.50;
      var th = (bottom - top) * 0.40;
      ctx.beginPath();
      ctx.ellipse(cx, bottom - th * 0.30, tw, th, 0, 0, TAU);
      ctx.fillStyle = '#F08F92';
      ctx.fill();
      ctx.restore();
    }
  };

  /** 圆角矩形路径（胶囊形） */
  function roundRectPath(ctx, x, y, w, h, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.lineTo(x + w - r, y);
    ctx.quadraticCurveTo(x + w, y, x + w, y + r);
    ctx.lineTo(x + w, y + h - r);
    ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
    ctx.lineTo(x + r, y + h);
    ctx.quadraticCurveTo(x, y + h, x, y + h - r);
    ctx.lineTo(x, y + r);
    ctx.quadraticCurveTo(x, y, x + r, y);
    ctx.closePath();
  }

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
