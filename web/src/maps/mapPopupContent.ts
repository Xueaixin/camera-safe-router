import type { HandoffMarkerData } from '@/types/map';
import type { CameraView } from '@/types/api';
import type { MapEndpointTarget, MapSelection } from '@/types/map';

function coordinateText(coordinate: { lng: number; lat: number }) {
  return `坐标：${coordinate.lng.toFixed(6)}, ${coordinate.lat.toFixed(6)}`;
}

function textLine(text: string, className?: string) {
  const line = document.createElement('span');
  if (className) line.className = className;
  line.textContent = text;
  return line;
}

async function copyText(text: string): Promise<void> {
  try {
    if (!navigator.clipboard?.writeText) throw new Error('Clipboard API unavailable');
    await navigator.clipboard.writeText(text);
    return;
  } catch {
    const textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.readOnly = true;
    textarea.style.position = 'fixed';
    textarea.style.opacity = '0';
    textarea.style.pointerEvents = 'none';
    document.body.append(textarea);
    textarea.focus();
    textarea.select();
    textarea.setSelectionRange(0, text.length);
    const copied = typeof document.execCommand === 'function' && document.execCommand('copy');
    textarea.remove();
    if (!copied) throw new Error('Copy command failed');
  }
}

export function createCameraPopup(camera: CameraView) {
  const content = document.createElement('div');
  content.className = 'map-info-popup camera-popup';
  content.dataset.testid = 'camera-popup';

  const title = document.createElement('strong');
  title.textContent = camera.cameraType;
  content.append(
    title,
    textLine(camera.address),
    textLine(camera.directionText ? `方向：${camera.directionText}` : '方向：未标注'),
    textLine(coordinateText(camera), 'map-info-popup__coordinate'),
  );
  return content;
}

export function createMapPointPopup(
  selection: MapSelection,
  onSelect: (target: MapEndpointTarget) => void,
) {
  const content = document.createElement('div');
  content.className = 'map-info-popup map-point-popup';
  content.dataset.testid = 'map-point-popup';

  const title = document.createElement('strong');
  title.textContent = selection.suggestedName;
  const actions = document.createElement('div');
  actions.className = 'map-point-popup__actions';

  const createAction = (target: MapEndpointTarget, label: string) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = `map-point-popup__action map-point-popup__action--${target}`;
    button.dataset.testid = `set-${target}-from-map`;
    button.textContent = label;
    button.addEventListener('pointerdown', (event) => event.stopPropagation());
    button.addEventListener('click', (event) => {
      event.stopPropagation();
      onSelect(target);
    });
    return button;
  };

  actions.append(createAction('start', '设为起点'), createAction('end', '设为终点'));
  content.append(
    title,
    textLine(coordinateText(selection.coordinate), 'map-info-popup__coordinate'),
    actions,
  );
  return content;
}

export function createNavigationHandoffPopup(
  data: HandoffMarkerData,
  description: Promise<string>,
  amapNavigationUrl: string,
  onSelectSafeSegment: () => void,
) {
  const content = document.createElement('div');
  content.className = 'map-info-popup handoff-popup';
  content.dataset.testid = 'handoff-popup';

  const outbound = data.crossing.direction === 'OUTBOUND';
  const highway = data.navigationHandoff.type === 'HIGHWAY';
  const title = document.createElement('strong');
  title.textContent = outbound ? '环内路线终点' : '环内路线起点';
  const road = data.navigationHandoff.roadName
    || (highway ? '受控区外高速交接点' : '普通道路交接点');
  const roadLine = textLine(`交接道路：${road}`, 'handoff-popup__road');
  const location = textLine(
    highway ? '正在确认高速交接位置' : '正在获取交接点附近地标',
    'handoff-popup__location',
  );
  const feedback = textLine('', 'handoff-popup__feedback');
  feedback.setAttribute('role', 'status');

  const actions = document.createElement('div');
  actions.className = 'map-point-popup__actions';
  const segmentButton = document.createElement('button');
  segmentButton.type = 'button';
  segmentButton.className = 'map-point-popup__action map-point-popup__action--handoff';
  segmentButton.dataset.testid = 'show-safe-segment';
  segmentButton.textContent = outbound ? '设为终点' : '设为起点';
  segmentButton.addEventListener('pointerdown', (event) => event.stopPropagation());
  segmentButton.addEventListener('click', (event) => {
    event.stopPropagation();
    onSelectSafeSegment();
  });

  const navigationLink = document.createElement('a');
  navigationLink.className = 'map-point-popup__action map-point-popup__action--navigation';
  navigationLink.dataset.testid = 'open-amap-navigation';
  navigationLink.textContent = '高德导航';
  navigationLink.href = amapNavigationUrl;
  navigationLink.target = '_blank';
  navigationLink.rel = 'noopener noreferrer';
  navigationLink.addEventListener('pointerdown', (event) => event.stopPropagation());
  navigationLink.addEventListener('click', (event) => event.stopPropagation());

  const copyButton = document.createElement('button');
  copyButton.type = 'button';
  copyButton.className = 'map-point-popup__action map-point-popup__action--copy';
  copyButton.dataset.testid = 'copy-handoff-landmark';
  copyButton.textContent = highway ? '复制道路' : '复制地标';
  copyButton.disabled = true;
  copyButton.addEventListener('pointerdown', (event) => event.stopPropagation());

  let resolvedDescription = '';
  void description.then(
    (value) => {
      if (!value) {
        location.textContent = '暂无可靠的附近地标';
        return;
      }
      resolvedDescription = value;
      location.textContent = highway ? `交接位置：${value}` : `附近地标：${value}`;
      copyButton.disabled = false;
    },
    () => {
      location.textContent = '暂无可靠的附近地标';
    },
  );
  copyButton.addEventListener('click', async (event) => {
    event.stopPropagation();
    if (!resolvedDescription) return;
    try {
      await copyText(resolvedDescription);
      feedback.textContent = '地标描述已复制';
    } catch {
      feedback.textContent = '复制失败，请稍后重试';
    }
  });

  actions.append(segmentButton, navigationLink, copyButton);
  content.append(title, roadLine, location, actions, feedback);
  return content;
}
