/**
 * 全息形象渲染层（v3：悬浮胸像）。
 *
 * 设计要点：
 * 1. 纯 Canvas 2D 绘制，不做任何模型推理 —— 这是 RK3568 能稳跑 30fps 的前提
 * 2. 渐变、路径等昂贵对象在 resize 时缓存，不进渲染循环
 * 3. 口型由音量包络驱动，attack 快、release 慢，避免抖动
 * 4. 呼吸、眨眼、漂浮粒子提供"活着"的微动
 * 5. 胸像底部用 alpha 渐隐，制造"从光里浮现"的全息感
 *
 * 比例参考（800×1280）：头宽 = 0.21W，肩宽 ≈ 2.3 个头宽，颈长 ≈ 0.03H。
 */
(function (global) {
  'use strict';

  var TAU = Math.PI * 2;

  function AvatarStage(canvas) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');

    this.W = 0;
    this.H = 0;
    this.dpr = 1;

    this.level = 0;
    this.mouth = 0;
    this.state = 'idle';

    this.blink = 1;
    this.nextBlink = 2000;
    this.blinkStart = -1;

    this.particles = [];
    this.glitch = 0;
    this.nextGlitch = 5000;

    this.t0 = performance.now();
    this.lastFrame = 0;
    this.raf = null;

    this.cache = {};

    this._loop = this._loop.bind(this);
    this.resize = this.resize.bind(this);

    global.addEventListener('resize', this.resize);
    this.resize();
    this._initParticles();
  }

  /** 统一的比例计算，改形象只动这里 */
  AvatarStage.prototype._geo = function () {
    var W = this.W, H = this.H;
    var headRx = W * 0.105;
    var headRy = W * 0.132;
    var headCy = H * 0.238;
    var neckHalf = headRx * 0.24;
    var neckTop = headCy + headRy * 0.85;     // 颈宽要小于下颌宽度，否则脖子会"方"
    var shoulderY = neckTop + H * 0.052;      // 肩线要比下巴低一截，否则没有脖子
    var halfShoulder = W * 0.212;
    var armX = W * 0.186;                     // 手臂比肩窄，才有"肩章"的坡
    var bodyBottom = H * 0.72;                // 胸像截断，留出底座与字幕空间

    return {
      cx: W / 2,
      headRx: headRx,
      headRy: headRy,
      headCy: headCy,
      neckHalf: neckHalf,
      neckTop: neckTop,
      shoulderY: shoulderY,
      halfShoulder: halfShoulder,
      armX: armX,
      bodyBottom: bodyBottom
    };
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
    var g = this._geo();

    var bg = ctx.createRadialGradient(W / 2, H * 0.38, 0, W / 2, H * 0.38, H * 0.62);
    bg.addColorStop(0, 'rgba(14, 52, 74, 0.40)');
    bg.addColorStop(0.5, 'rgba(6, 24, 36, 0.28)');
    bg.addColorStop(1, 'rgba(0, 0, 0, 0)');
    this.cache.bg = bg;

    // 身体：顶部略亮，向下渐隐
    var fig = ctx.createLinearGradient(0, g.neckTop, 0, g.bodyBottom);
    fig.addColorStop(0, 'rgba(165, 250, 255, 0.17)');
    fig.addColorStop(0.30, 'rgba(95, 215, 242, 0.11)');
    fig.addColorStop(0.70, 'rgba(50, 150, 200, 0.05)');
    fig.addColorStop(1, 'rgba(30, 110, 160, 0)');
    this.cache.fig = fig;

    var edge = ctx.createLinearGradient(0, g.neckTop, 0, g.bodyBottom);
    edge.addColorStop(0, 'rgba(180, 252, 255, 0.50)');
    edge.addColorStop(0.55, 'rgba(140, 235, 255, 0.20)');
    edge.addColorStop(1, 'rgba(120, 220, 255, 0)');
    this.cache.edge = edge;

    var hair = ctx.createLinearGradient(0, g.headCy - g.headRy * 1.3, 0, g.shoulderY);
    hair.addColorStop(0, 'rgba(42, 152, 192, 0.50)');
    hair.addColorStop(1, 'rgba(20, 90, 130, 0.12)');
    this.cache.hair = hair;
  };

  AvatarStage.prototype._initParticles = function () {
    this.particles = [];
    for (var i = 0; i < 26; i++) {
      this.particles.push({
        x: Math.random(),
        y: Math.random(),
        r: 0.6 + Math.random() * 1.6,
        sp: 0.006 + Math.random() * 0.014,
        a: 0.12 + Math.random() * 0.30,
        ph: Math.random() * TAU
      });
    }
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

    if (now - this.lastFrame < 33) return;   // 锁 30fps
    this.lastFrame = now;

    this._update(now);
    this._draw(now);
  };

  AvatarStage.prototype._update = function (now) {
    var k = this.level > this.mouth ? 0.45 : 0.16;
    this.mouth += (this.level - this.mouth) * k;
    if (this.mouth < 0.004) this.mouth = 0;

    if (this.blinkStart < 0 && now > this.nextBlink) {
      this.blinkStart = now;
    }
    if (this.blinkStart >= 0) {
      var p = (now - this.blinkStart) / 130;
      if (p >= 1) {
        this.blinkStart = -1;
        this.blink = 1;
        this.nextBlink = now + 2200 + Math.random() * 3200;
      } else {
        this.blink = Math.abs(Math.cos(p * Math.PI));
      }
    }

    if (now > this.nextGlitch) {
      this.glitch = 1;
      this.nextGlitch = now + 5000 + Math.random() * 7000;
    }
    this.glitch *= 0.9;
    if (this.glitch < 0.01) this.glitch = 0;

    for (var i = 0; i < this.particles.length; i++) {
      var pt = this.particles[i];
      pt.y -= pt.sp * 0.01;
      if (pt.y < -0.05) {
        pt.y = 1.05;
        pt.x = Math.random();
      }
    }
  };

  AvatarStage.prototype._draw = function (now) {
    var ctx = this.ctx;
    var W = this.W, H = this.H;
    var t = (now - this.t0) / 1000;

    ctx.clearRect(0, 0, W, H);
    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, W, H);

    ctx.fillStyle = this.cache.bg;
    ctx.fillRect(0, 0, W, H);

    this._drawParticles(ctx, W, H, t);
    this._drawPedestal(ctx, W, H, t);
    this._drawFigure(ctx, W, H, t);
    this._drawGlitch(ctx, W, H);
  };

  AvatarStage.prototype._drawParticles = function (ctx, W, H, t) {
    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    for (var i = 0; i < this.particles.length; i++) {
      var p = this.particles[i];
      var a = p.a * (0.6 + 0.4 * Math.sin(t * 0.9 + p.ph));
      ctx.fillStyle = 'rgba(150, 240, 255, ' + a.toFixed(3) + ')';
      ctx.beginPath();
      ctx.arc(p.x * W, p.y * H, p.r, 0, TAU);
      ctx.fill();
    }
    ctx.restore();
  };

  AvatarStage.prototype._drawPedestal = function (ctx, W, H, t) {
    var cx = W / 2;
    var cy = H * 0.905;
    var rx = W * 0.26;
    var ry = H * 0.016;

    ctx.save();
    ctx.globalCompositeOperation = 'lighter';

    var g = ctx.createRadialGradient(cx, cy, 0, cx, cy, rx);
    g.addColorStop(0, 'rgba(90, 230, 255, ' + (0.16 + this.mouth * 0.18) + ')');
    g.addColorStop(0.55, 'rgba(40, 140, 200, 0.06)');
    g.addColorStop(1, 'rgba(0, 0, 0, 0)');
    ctx.fillStyle = g;
    ctx.beginPath();
    ctx.ellipse(cx, cy, rx, ry * 3.4, 0, 0, TAU);
    ctx.fill();

    ctx.strokeStyle = 'rgba(120, 235, 255, ' + (0.13 + this.mouth * 0.14) + ')';
    ctx.lineWidth = 1;
    for (var i = 1; i <= 2; i++) {
      var k = i / 2;
      ctx.beginPath();
      ctx.ellipse(cx, cy, rx * k, ry * k, 0, 0, TAU);
      ctx.stroke();
    }
    ctx.restore();
  };

  AvatarStage.prototype._drawFigure = function (ctx, W, H, t) {
    var g = this._geo();
    var cx = g.cx;
    var headRx = g.headRx, headRy = g.headRy, headCy = g.headCy;
    var neckHalf = g.neckHalf, neckTop = g.neckTop;
    var halfShoulder = g.halfShoulder, armX = g.armX, bodyBottom = g.bodyBottom;

    var breathe = Math.sin(t * (TAU / 4.6)) * (H * 0.0030);
    var sway = Math.sin(t * (TAU / 7.3)) * (W * 0.0022);

    ctx.save();
    ctx.translate(sway, breathe);

    // 头部外发光
    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    var halo = ctx.createRadialGradient(cx, headCy, headRx * 0.6, cx, headCy, headRx * 3.0);
    halo.addColorStop(0, 'rgba(120, 240, 255, 0.09)');
    halo.addColorStop(1, 'rgba(120, 240, 255, 0)');
    ctx.fillStyle = halo;
    ctx.beginPath();
    ctx.arc(cx, headCy, headRx * 3.0, 0, TAU);
    ctx.fill();
    ctx.restore();

    // 后层头发
    ctx.beginPath();
    ctx.ellipse(cx, headCy - headRy * 0.10, headRx * 1.10, headRy * 1.08, 0, 0, TAU);
    ctx.fillStyle = this.cache.hair;
    ctx.fill();

    // 侧发：从耳侧垂到肩
    this._sideLock(ctx, cx - headRx * 0.92, headCy - headRy * 0.05, headRx * 0.34, headRy * 2.45, -1);
    this._sideLock(ctx, cx + headRx * 0.92, headCy - headRy * 0.05, headRx * 0.34, headRy * 2.45, 1);

    // ---------- 身体 ----------
    ctx.beginPath();
    ctx.moveTo(cx - halfShoulder, g.shoulderY + H * 0.018);
    // 肩线：接近水平，只有轻微下斜，否则会变成"斗篷"
    ctx.bezierCurveTo(
      cx - halfShoulder * 0.66, g.shoulderY - H * 0.006,
      cx - neckHalf * 3.4, neckTop + H * 0.004,
      cx - neckHalf, neckTop
    );
    ctx.lineTo(cx - neckHalf, neckTop);
    // 颈部顶端做成一道弧，藏进头部轮廓里，避免出现方角
    ctx.quadraticCurveTo(cx, headCy + headRy * 0.52, cx + neckHalf, neckTop);
    ctx.bezierCurveTo(
      cx + neckHalf * 3.4, neckTop + H * 0.004,
      cx + halfShoulder * 0.66, g.shoulderY - H * 0.006,
      cx + halfShoulder, g.shoulderY + H * 0.018
    );
    // 手臂外缘：向下略微外扩，到底部渐隐
    ctx.bezierCurveTo(
      cx + halfShoulder * 1.04, g.shoulderY + H * 0.16,
      cx + armX, bodyBottom - H * 0.20,
      cx + armX, bodyBottom
    );
    ctx.lineTo(cx - armX, bodyBottom);
    ctx.bezierCurveTo(
      cx - armX, bodyBottom - H * 0.20,
      cx - halfShoulder * 1.04, g.shoulderY + H * 0.16,
      cx - halfShoulder, g.shoulderY + H * 0.018
    );
    ctx.closePath();

    ctx.fillStyle = this.cache.fig;
    ctx.fill();
    ctx.strokeStyle = this.cache.edge;
    ctx.lineWidth = 1.3;
    ctx.stroke();

    // 衣领 V 形
    ctx.beginPath();
    ctx.moveTo(cx - neckHalf * 1.7, neckTop + H * 0.004);
    ctx.lineTo(cx, neckTop + H * 0.052);
    ctx.lineTo(cx + neckHalf * 1.7, neckTop + H * 0.004);
    ctx.strokeStyle = 'rgba(170, 245, 255, 0.20)';
    ctx.lineWidth = 1.2;
    ctx.stroke();

    // 手臂分隔：两条很淡的线，避免身体变成一整块"石板"
    ctx.strokeStyle = 'rgba(170, 245, 255, 0.09)';
    ctx.lineWidth = 1;
    for (var s = -1; s <= 1; s += 2) {
      ctx.beginPath();
      ctx.moveTo(cx + s * halfShoulder * 0.62, g.shoulderY + H * 0.02);
      ctx.bezierCurveTo(
        cx + s * armX * 0.98, g.shoulderY + H * 0.10,
        cx + s * armX * 0.95, bodyBottom - H * 0.10,
        cx + s * armX * 0.92, bodyBottom
      );
      ctx.stroke();
    }

    // ---------- 头部 ----------
    // 先用偏暗的填充盖住颈部顶端的描边（否则下巴下面会出现一道弧线）
    ctx.beginPath();
    ctx.ellipse(cx, headCy, headRx, headRy, 0, 0, TAU);
    ctx.fillStyle = 'rgba(5, 16, 24, 0.90)';
    ctx.fill();
    ctx.fillStyle = 'rgba(140, 235, 255, 0.14)';
    ctx.fill();
    ctx.strokeStyle = 'rgba(190, 252, 255, 0.55)';
    ctx.lineWidth = 1.5;
    ctx.stroke();

    // 刘海
    ctx.beginPath();
    ctx.moveTo(cx - headRx * 0.97, headCy - headRy * 0.28);
    ctx.bezierCurveTo(
      cx - headRx * 0.90, headCy - headRy * 1.02,
      cx + headRx * 0.90, headCy - headRy * 1.02,
      cx + headRx * 0.97, headCy - headRy * 0.28
    );
    ctx.bezierCurveTo(
      cx + headRx * 0.50, headCy - headRy * 0.66,
      cx - headRx * 0.50, headCy - headRy * 0.66,
      cx - headRx * 0.97, headCy - headRy * 0.28
    );
    ctx.closePath();
    ctx.fillStyle = 'rgba(58, 170, 210, 0.40)';
    ctx.fill();

    // ---------- 五官 ----------
    var eyeY = headCy + headRy * 0.04;
    var eyeX = headRx * 0.42;
    var eyeRx = headRx * 0.150;
    var eyeRy = headRx * 0.098;
    var blink = 0.08 + this.blink * 0.92;

    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    ctx.fillStyle = 'rgba(205, 255, 255, 0.92)';
    ctx.shadowColor = 'rgba(126, 249, 255, 0.9)';
    ctx.shadowBlur = 9;
    ctx.beginPath();
    ctx.ellipse(cx - eyeX, eyeY, eyeRx, Math.max(0.6, eyeRy * blink), 0, 0, TAU);
    ctx.fill();
    ctx.beginPath();
    ctx.ellipse(cx + eyeX, eyeY, eyeRx, Math.max(0.6, eyeRy * blink), 0, 0, TAU);
    ctx.fill();
    ctx.restore();

    // 上眼睑
    ctx.strokeStyle = 'rgba(205, 252, 255, 0.50)';
    ctx.lineWidth = 1.1;
    ctx.beginPath();
    ctx.ellipse(cx - eyeX, eyeY, eyeRx * 1.10, Math.max(0.8, eyeRy * 1.35 * blink), 0, Math.PI * 1.06, Math.PI * 1.94);
    ctx.stroke();
    ctx.beginPath();
    ctx.ellipse(cx + eyeX, eyeY, eyeRx * 1.10, Math.max(0.8, eyeRy * 1.35 * blink), 0, Math.PI * 1.06, Math.PI * 1.94);
    ctx.stroke();

    // 鼻
    ctx.strokeStyle = 'rgba(180, 245, 255, 0.16)';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(cx - headRx * 0.02, headCy + headRy * 0.24);
    ctx.lineTo(cx + headRx * 0.04, headCy + headRy * 0.42);
    ctx.stroke();

    // 嘴
    var mouthY = headCy + headRy * 0.60;
    var mouthW = headRx * 0.22;
    var mouthH = 1.2 + this.mouth * headRx * 0.13;

    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    ctx.fillStyle = 'rgba(200, 255, 255, ' + (0.35 + this.mouth * 0.40) + ')';
    ctx.shadowColor = 'rgba(126, 249, 255, 0.8)';
    ctx.shadowBlur = 7 + this.mouth * 9;
    ctx.beginPath();
    ctx.ellipse(cx, mouthY, mouthW, mouthH, 0, 0, TAU);
    ctx.fill();
    ctx.restore();

    // ---------- 全息横纹 ----------
    ctx.save();
    ctx.beginPath();
    ctx.rect(0, headCy - headRy * 1.3, W, bodyBottom - headCy + headRy * 1.3);
    ctx.clip();
    ctx.globalCompositeOperation = 'lighter';
    ctx.strokeStyle = 'rgba(220, 255, 255, ' + (0.040 + this.mouth * 0.045) + ')';
    ctx.lineWidth = 1;
    for (var i = 0; i < 14; i++) {
      var y = headCy - headRy * 1.2 + i * (bodyBottom - headCy) / 13;
      ctx.beginPath();
      ctx.moveTo(cx - W * 0.40, y);
      ctx.lineTo(cx + W * 0.40, y);
      ctx.stroke();
    }
    ctx.restore();

    ctx.restore();
  };

  /** 侧发：从耳侧垂下的渐窄长条 */
  AvatarStage.prototype._sideLock = function (ctx, x, y, w, h, dir) {
    ctx.beginPath();
    ctx.moveTo(x, y);
    ctx.bezierCurveTo(
      x + dir * w * 0.95, y + h * 0.22,
      x + dir * w * 0.75, y + h * 0.70,
      x + dir * w * 0.25, y + h
    );
    ctx.bezierCurveTo(
      x + dir * w * 0.15, y + h * 0.62,
      x - dir * w * 0.05, y + h * 0.24,
      x, y
    );
    ctx.closePath();
    ctx.fillStyle = 'rgba(48, 158, 198, 0.26)';
    ctx.fill();
  };

  AvatarStage.prototype._drawGlitch = function (ctx, W, H) {
    if (this.glitch <= 0.02) return;
    var y = H * (0.18 + Math.random() * 0.5);
    var h = 2 + Math.random() * 4;
    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    ctx.fillStyle = 'rgba(150, 250, 255, ' + (this.glitch * 0.15).toFixed(3) + ')';
    ctx.fillRect(0, y, W, h);
    ctx.fillStyle = 'rgba(255, 120, 200, ' + (this.glitch * 0.06).toFixed(3) + ')';
    ctx.fillRect(0, y + h + 1, W, 1);
    ctx.restore();
  };

  global.AvatarStage = AvatarStage;
})(window);
