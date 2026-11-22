package com.bmp.tenant.application.query;

import tools.jackson.databind.node.ObjectNode;

/**
 * The effective values of one setting group (defaults overlaid with stored values).
 *
 * @param group the group code
 * @param version the group version (0 while only defaults apply)
 * @param values effective values by key
 */
public record SettingGroupView(String group, long version, ObjectNode values) {}
