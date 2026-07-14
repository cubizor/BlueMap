<template>
  <div class="filter-menu">
    <div class="filter-button" :class="{active: open}" @click="open = !open">
      <svg viewBox="0 0 30 30">
        <path d="M5,6h20l-7.5,9v8l-5,2v-10L5,6z"/>
      </svg>
      <span class="label">{{ $t("filters.button") }}</span>
    </div>
    <div v-if="open" class="filter-panel">
      <div class="group-title">{{ $t("filters.ores") }}</div>
      <div v-for="set in oreSets" :key="set.id" class="filter-row" @click="toggle(set)">
        <span class="row-label">{{ set.label }}</span>
        <div class="switch" :class="{on: set.visible}"></div>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: "FilterMenu",
  data() {
    return {
      open: false,
      markers: this.$bluemap.mapViewer.markers.data,
    }
  },
  computed: {
    oreSets() {
      return this.markers.markerSets
          .filter(set => set.toggleable && set.id.startsWith("ore-"))
          .sort((a, b) => (a.sorting || 0) - (b.sorting || 0));
    }
  },
  methods: {
    toggle(set) {
      // eslint-disable-next-line vue/no-mutating-props
      set.visible = !set.visible;
      set.saveState();
    }
  }
}
</script>

<style lang="scss">
@import "/src/scss/variables.scss";

.filter-menu {
  position: relative;
  pointer-events: auto;

  .filter-button {
    display: flex;
    align-items: center;
    gap: 0.4em;

    cursor: pointer;
    user-select: none;

    height: 2em;
    padding: 0 0.75em 0 0.5em;

    background-color: var(--theme-bg);
    color: var(--theme-fg);

    &:hover {
      background-color: var(--theme-bg-hover);
    }

    &.active {
      background-color: var(--theme-bg-light);
    }

    svg {
      height: 1.4em;
      width: 1.4em;
      flex-shrink: 0;
      fill: var(--theme-fg-light);
    }

    .label {
      line-height: 2em;
      white-space: nowrap;
    }
  }

  .filter-panel {
    position: absolute;
    top: calc(100% + 0.5em);
    left: 0;

    min-width: 14em;
    padding: 0.5em 0;

    background-color: var(--theme-bg);
    color: var(--theme-fg);

    .group-title {
      padding: 0.25em 0.75em 0.5em;
      font-size: 0.8em;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--theme-fg-light);
    }

    .filter-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1em;

      cursor: pointer;
      user-select: none;

      padding: 0.5em 0.75em;

      &:hover {
        background-color: var(--theme-bg-hover);
      }

      .row-label {
        white-space: nowrap;
      }

      .switch {
        flex-shrink: 0;
        height: 1em;
        width: 2em;

        border-radius: 1em;
        background-color: var(--theme-bg-light);

        transition: background-color 0.3s;

        &::after {
          content: "";
          display: block;
          width: 0.75em;
          height: 0.75em;
          border-radius: 100%;

          background-color: var(--theme-bg);

          position: relative;
          top: 0.125em;
          left: 0.125em;

          transition: left 0.3s;
        }

        &.on {
          background-color: var(--theme-switch-button-on);

          &::after {
            left: 1.125em;
          }
        }
      }
    }
  }
}
</style>
